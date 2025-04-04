package no.nav.kafka.processor

import kotlinx.serialization.SerializationException
import org.apache.kafka.streams.processor.api.Processor
import org.apache.kafka.streams.processor.api.ProcessorContext
import org.apache.kafka.streams.processor.api.Record

class UnhandledRecordProcessingException(cause: Throwable): Exception("Unhandled record processing exception", cause)

/*
* Processing a message expects a explicit result of either COMMIT, RETRY or SKIP to be the result of processing a message.
* If an exception is thrown the error
* */
class ExplicitResultProcessor(val processRecord: ProcessRecord): Processor<String, String, String, String> {
    private lateinit var context: ProcessorContext<String, String>
    override fun init(context: ProcessorContext<String, String>) {
        this.context = context
    }
    override fun process(record: Record<String, String>) {
        runCatching {
            processRecord(record)
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