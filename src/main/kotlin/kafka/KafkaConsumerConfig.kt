package no.nav.kafka

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.KafkaStreams
import org.apache.kafka.streams.StreamsBuilder
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.processor.api.Processor
import org.apache.kafka.streams.processor.api.ProcessorContext
import org.apache.kafka.streams.processor.api.ProcessorSupplier
import org.apache.kafka.streams.processor.api.Record
import java.util.Properties

suspend fun startKafkaStreams(topic: String, processRecord: ProcessRecord): KafkaStreams {
    return withContext(Dispatchers.IO) {
        val config = Properties().apply {
            put(StreamsConfig.APPLICATION_ID_CONFIG, "ktor-kafka-stream-app")
            put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092")
            put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().javaClass.name)
            put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().javaClass.name)
            put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, "1000") // Control commit interval
            put(StreamsConfig.producerPrefix(ProducerConfig.RETRIES_CONFIG), "5") // Enable retries
            put(StreamsConfig.producerPrefix(ProducerConfig.ACKS_CONFIG), "all") // Ensure strong consistency
            put(StreamsConfig.PRODUCTION_EXCEPTION_HANDLER_CLASS_CONFIG, RetryProductionExceptionHandler::class.java.name)
        }

        val builder = StreamsBuilder()
        val sourceStream = builder.stream<String, String>(topic)

        sourceStream.process(object : ProcessorSupplier<String, String, String, String> {
            override fun get(): Processor<String, String, String, String> {
                return CatchingProcessor(processRecord)
            }
        })
        KafkaStreams(builder.build(), config).apply { start() }
    }
}

class UnhandledRecordProcessingException(cause: Throwable): Exception("Unhandled record processing exception", cause)

class CatchingProcessor(val processRecord: ProcessRecord): Processor<String, String, String, String> {
    private lateinit var context: ProcessorContext<String, String>
    override fun init(context: ProcessorContext<String, String>) {
        this.context = context
    }
    override fun process(record: Record<String, String>) {
        runCatching { processRecord(record) }
            .onSuccess {
                when (it) {
                    RecordProcessingResult.COMMIT -> context.commit()
                    RecordProcessingResult.RETRY -> {}
                    RecordProcessingResult.FAIL -> {}
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
