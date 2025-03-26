package no.nav.kafka

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.common.serialization.StringSerializer
import org.apache.kafka.streams.KafkaStreams
import org.apache.kafka.streams.StreamsBuilder
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.processor.api.Processor
import org.apache.kafka.streams.processor.api.ProcessorSupplier
import java.util.Properties

suspend fun startKafkaStreams(topic: String, onConsume: () -> Unit): KafkaStreams {
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
        val sourceStream = builder.stream<String, String>("test-topic")

        val processorSupplier = ProcessorSupplier<String, String, String, String> {
            fun get(): Processor<String, String, String, String> {
                val processor: Processor<String, String, String, String> = { }
                return processor
            }
        }

        sourceStream.process(ProcessorSupplier<String, String, String, String> { context, key: String, value: String ->
            try {
                println("Consumed: $key -> $value")
                context.commit() // Commit the offset if processing is successful
            } catch (e: SerializationException) {
                println("Skipping message due to deserialization error: ${e.message}")
                context.commit() // Skip and move to the next message
            } catch (e: Exception) {
                println("Fatal error, stopping processing: ${e.message}")
            }
        })

        KafkaStreams(builder.build(), config).apply { start() }
    }
}

suspend fun createKafkaProducer(): KafkaProducer<String, String> {
    return withContext(Dispatchers.IO) {
        val producerConfig = Properties().apply {
            put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092")
            put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
            put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
            put(ProducerConfig.RETRIES_CONFIG, "5") // Enable retries
        }
        KafkaProducer(producerConfig)
    }
}