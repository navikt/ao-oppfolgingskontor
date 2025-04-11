package no.nav.kafka.processor

import kotlinx.serialization.SerializationException
import no.nav.kafka.EndringPaOppfolgingsBrukerConsumer
import org.apache.kafka.streams.processor.api.Processor
import org.apache.kafka.streams.processor.api.ProcessorContext
import org.apache.kafka.streams.processor.api.Record
import org.slf4j.LoggerFactory
import kotlin.jvm.optionals.getOrNull

class UnhandledRecordProcessingException(cause: Throwable): Exception("Unhandled record processing exception", cause)

/*
* Processing a message expects a explicit result of either COMMIT, RETRY or SKIP to be the result of processing a message.
* If an exception is thrown the error
* */
class ExplicitResultProcessor(val processRecord: ProcessRecord): Processor<String, String, String, String> {
    private lateinit var context: ProcessorContext<String, String>
    val log = LoggerFactory.getLogger(ExplicitResultProcessor::class.java)
    override fun init(context: ProcessorContext<String, String>) {
        this.context = context
    }


    override fun process(record: Record<String, String>) {

        runCatching {
            context.recordMetadata().map { log.info("Kafka topic: ${it.topic()}, partition: ${it.partition()}, offset: ${it.offset()}") }
            processRecord(record, context.recordMetadata().getOrNull())
        }
            .onSuccess {
                when (it) {
                    RecordProcessingResult.COMMIT -> context.commit()
                    RecordProcessingResult.RETRY -> {}
                    RecordProcessingResult.SKIP -> context.commit()
                }
            }
            .onFailure { exception ->
                when (exception) {
                    is SerializationException -> {
                        throw exception
                    }
                    else -> throw UnhandledRecordProcessingException(cause = exception)
                }
            }
    }
}