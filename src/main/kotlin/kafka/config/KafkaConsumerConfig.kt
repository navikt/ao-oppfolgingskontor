package no.nav.kafka.config

import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde
import io.ktor.server.config.*
import net.javacrumbs.shedlock.core.LockProvider
import no.nav.kafka.exceptionHandler.RetryIfRetriableExceptionHandler
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
import java.util.Properties

sealed class TopicConsumer(
    val topic: String,
)
class StringTopicConsumer(
    topic: String,
    val processRecord: ProcessRecord<String, String, Unit, Unit>,
): TopicConsumer(topic)
class AvroTopicConsumer(
    topic: String,
    val processRecord: ProcessRecord<Any, Personhendelse, Unit, Unit>,
    val valueSerde: SpecificAvroSerde<Personhendelse>,
    val keySerde: Serde<Any>
): TopicConsumer(topic)

fun configureTopology(
    topicAndConsumers: List<TopicConsumer>,
    lockProvider: LockProvider
): Topology {
    val builder = StreamsBuilder()

    topicAndConsumers.forEach { topicAndConsumer ->
        when (topicAndConsumer) {
            is StringTopicConsumer -> {
                RetryableTopology.addTerminalRetryableProcessor(
                    builder = builder,
                    inputTopic = topicAndConsumer.topic,
                    keySerde = Serdes.String(),
                    valueSerde = Serdes.String(),
                    businessLogic = { topicAndConsumer.processRecord(it, null) },
                    config = RetryConfig(topicAndConsumer.topic),
                    lockProvider = lockProvider
                )
            }
            is AvroTopicConsumer -> {
                RetryableTopology.addTerminalRetryableProcessor(
                    builder = builder,
                    inputTopic = topicAndConsumer.topic,
                    keySerde = topicAndConsumer.keySerde,
                    valueSerde = topicAndConsumer.valueSerde,
                    businessLogic = { topicAndConsumer.processRecord(it, null) },
                    config = RetryConfig(topicAndConsumer.topic),
                    lockProvider = lockProvider
                )
            }
        }
    }
    return builder.build()
}

fun configureKafkaStreams(config: ApplicationConfig): Properties {
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
    put(StreamsConfig.PROCESSING_EXCEPTION_HANDLER_CLASS_CONFIG, RetryIfRetriableExceptionHandler::class.java.name)
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
