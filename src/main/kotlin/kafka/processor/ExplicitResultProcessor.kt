package no.nav.kafka.processor

import kotlinx.serialization.SerializationException
import no.nav.kafka.exceptionHandler.KafkaStreamsTaskMonitor
import no.nav.kafka.exceptionHandler.TaskProcessingException
import org.apache.kafka.streams.processor.api.Processor
import org.apache.kafka.streams.processor.api.ProcessorContext
import org.apache.kafka.streams.processor.api.Record
import org.slf4j.LoggerFactory
import kotlin.jvm.optionals.getOrNull

/*
* Processing a message expects a explicit result of either COMMIT, RETRY or SKIP to be the result of processing a message.
* If an exception is thrown the error
* */
class ExplicitResultProcessor(val processRecord: ProcessRecord, val monitor: KafkaStreamsTaskMonitor): Processor<String, String, String, String> {
    private lateinit var context: ProcessorContext<String, String>
    val log = LoggerFactory.getLogger(ExplicitResultProcessor::class.java)
    override fun init(context: ProcessorContext<String, String>) {
        this.context = context
        monitor.registerOrUpdateTaskGauge(context.taskId())
    }

    override fun process(record: Record<String, String>) {
        runCatching {
            context.recordMetadata().map { log.info("Kafka topic: ${it.topic()}, partition: ${it.partition()}, offset: ${it.offset()}") }
            processRecord(record, context.recordMetadata().getOrNull())
        }
            .onSuccess {
                when (it) {
                    RecordProcessingResult.RETRY -> {}
                    RecordProcessingResult.COMMIT, RecordProcessingResult.SKIP -> {
                        context.commit()
                        monitor.reportSuccessfulProcessing(context.taskId())
                    }
                }
            }
            .onFailure { exception ->
                when (exception) {
                    is SerializationException -> {
                        // TODO: store in db or forward the message to a dead letter queue, and register the error in metrics
                        throw exception
                    }
                    is TaskProcessingException -> throw exception
                    else -> throw TaskProcessingException(taskId = context.taskId(), message = "Processing exception",cause = exception)
                }
            }
    }
}