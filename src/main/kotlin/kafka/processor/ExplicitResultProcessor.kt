package no.nav.kafka.processor

import kotlinx.serialization.SerializationException
import org.apache.kafka.streams.processor.api.Processor
import org.apache.kafka.streams.processor.api.ProcessorContext
import org.apache.kafka.streams.processor.api.Record
import org.slf4j.LoggerFactory

class UnhandledRecordProcessingException(cause: Throwable): Exception("Unhandled record processing exception", cause)

/*
* Processing a message expects a explicit result of either COMMIT, RETRY or SKIP to be the result of processing a message.
* If an exception is thrown the error
* */
class ExplicitResultProcessor<K,V>(val processRecord: ProcessRecord<K,V, Unit, Unit>): Processor<K, V, Unit, Unit> {
    private lateinit var context: ProcessorContext<Unit, Unit>
    val log = LoggerFactory.getLogger(ExplicitResultProcessor::class.java)
    override fun init(context: ProcessorContext<Unit, Unit>) {
        this.context = context
    }

    override fun process(record: Record<K, V>) {

        runCatching {
            context.recordMetadata().map { log.info("Kafka topic: ${it.topic()}, partition: ${it.partition()}, offset: ${it.offset()}") }
            processRecord(record)
        }
            .onSuccess {
                when (it) {
                    is Commit -> context.commit()
                    is Forward -> context.forward(it.forwardedRecord)
                    is Retry -> Unit
                    is Skip -> context.commit()
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