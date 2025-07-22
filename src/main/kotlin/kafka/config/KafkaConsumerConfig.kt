package no.nav.kafka.config

import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde
import io.ktor.server.config.*
import net.javacrumbs.shedlock.core.LockProvider
import no.nav.kafka.retry.library.RetryConfig
import no.nav.kafka.retry.library.RetryableTopology
import no.nav.kafka.processor.ProcessRecord
import no.nav.person.pdl.leesah.Personhendelse
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.config.SslConfigs
import org.apache.kafka.common.serialization.Serde
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.StreamsBuilder
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.Topology
import org.apache.kafka.streams.errors.LogAndFailProcessingExceptionHandler
import java.util.Properties

sealed class TopicConsumer(val topic: String)

class StringTopicConsumer(
    topic: String,
    val processRecord: ProcessRecord<String, String, String, String>,
    val sink: StringStringSinkConfig? = null
): TopicConsumer(topic)

class AvroTopicConsumer(
    topic: String,
    val processRecord: ProcessRecord<String, Personhendelse, String, String>,
    val valueSerde: SpecificAvroSerde<Personhendelse>,
    val keySerde: Serde<String>,
    val sink: StringStringSinkConfig?
): TopicConsumer(topic)

open class SinkConfig<K, V>(
    val sinkName: String,
    val outputTopicName: String,
    val keySerde: Serde<K>,
    val valueSerde: Serde<V>,
) {
    override fun equals(other: Any?): Boolean {
        if (other !is SinkConfig<*, *>) return false
        if (sinkName != other.sinkName) return false
        if (outputTopicName != other.outputTopicName) return false
        if (keySerde != other.keySerde) return false
        if (valueSerde != other.valueSerde) return false
        return true
    }
}
class StringStringSinkConfig(
    sinkName: String,
    outputTopicName: String,
): SinkConfig<String, String>(
    sinkName,
    outputTopicName,
    Serdes.String(),
    Serdes.String(),
)

fun processorName(topic: String): String {
    return "${topic}-processor"
}

fun configureTopology(
    topicAndConsumers: List<TopicConsumer>,
    lockProvider: LockProvider,
): Topology {
    val builder = StreamsBuilder()

    topicAndConsumers.forEach { topicAndConsumer ->
        when (topicAndConsumer) {
            is StringTopicConsumer -> {
                RetryableTopology.addRetryableProcessor(
                    builder = builder,
                    inputTopic = topicAndConsumer.topic,
                    keyInSerde = Serdes.String(),
                    valueInSerde = Serdes.String(),
                    businessLogic = { topicAndConsumer.processRecord(it) },
                    config = RetryConfig(),
                    lockProvider = lockProvider
                )
            }
            is AvroTopicConsumer -> {
                RetryableTopology.addRetryableProcessor(
                    builder = builder,
                    inputTopic = topicAndConsumer.topic,
                    keyInSerde = topicAndConsumer.keySerde,
                    valueInSerde = topicAndConsumer.valueSerde,
                    businessLogic = { topicAndConsumer.processRecord(it) },
                    config = RetryConfig(),
                    lockProvider = lockProvider
                )
            }
        }
    }

    val topology = builder.build()
    topicAndConsumers.mapNotNull { topicConfig ->
        when (topicConfig) {
            is StringTopicConsumer -> topicConfig.sink?.let { processorName(topicConfig.topic) to it }
            is AvroTopicConsumer -> topicConfig.sink?.let { processorName(topicConfig.topic) to it }
        }
    }
        .groupBy { it.second.sinkName }
        .forEach { entry ->
            val sinkName = entry.key
            val parents = entry.value.map { it.first }
            require(entry.value.map { it.second }.distinct().size == 1) { "Sink configs for same sink name must be equal, found different configs for sink: $sinkName" }
            val sinkConfig = entry.value.first().second
            topology.addSink(
                sinkConfig.sinkName,
                sinkConfig.outputTopicName,
                sinkConfig.keySerde.serializer(),
                sinkConfig.valueSerde.serializer(),
                *parents.toTypedArray()
            )
        }

    return topology
}

fun kafkaStreamsProps(config: ApplicationConfig): Properties {
    val naisKafkaEnv = config.toKafkaEnv()
    return Properties()
        .streamsConfig(naisKafkaEnv, config)
        .streamsErrorHandlerConfig()
        .securityConfig(naisKafkaEnv)
}

private fun Properties.streamsConfig(config: NaisKafkaEnv, appConfig: ApplicationConfig): Properties {
    put(StreamsConfig.APPLICATION_ID_CONFIG, appConfig.property("kafka.application-id").getString())
    put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, config.KAFKA_BROKERS)
    put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().javaClass.name)
    put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().javaClass.name)
    put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, "1000") // Control commit interval
    put(StreamsConfig.producerPrefix(ProducerConfig.RETRIES_CONFIG), Int.MAX_VALUE) // Enable retries
    put(StreamsConfig.producerPrefix(ProducerConfig.ACKS_CONFIG), "all") // Ensure strong consistency
    return this
}

fun Properties.streamsErrorHandlerConfig(): Properties {
    put(StreamsConfig.PROCESSING_EXCEPTION_HANDLER_CLASS_CONFIG, LogAndFailProcessingExceptionHandler::class.java.name)
    return this
}

private fun Properties.securityConfig(config: NaisKafkaEnv): Properties {
    put(SslConfigs.SSL_TRUSTSTORE_TYPE_CONFIG, "JKS")
    put(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG, config.KAFKA_TRUSTSTORE_PATH)
    put(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG, config.KAFKA_CREDSTORE_PASSWORD)
    put(SslConfigs.SSL_KEYSTORE_TYPE_CONFIG, "PKCS12")
    put(SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG, config.KAFKA_KEYSTORE_PATH)
    put(SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG, config.KAFKA_CREDSTORE_PASSWORD)
    put(SslConfigs.SSL_KEY_PASSWORD_CONFIG, config.KAFKA_CREDSTORE_PASSWORD)
    put(SslConfigs.SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_CONFIG, "")
    put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SSL")
    return this
}
