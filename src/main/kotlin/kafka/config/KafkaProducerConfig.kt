package no.nav.kafka.config

import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.LongSerializer
import org.apache.kafka.common.serialization.StringSerializer
import java.util.Properties

fun createKafkaProducer(config: NaisKafkaEnv): KafkaProducer<String, String> {
    val producerConfig = Properties().apply {
        put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.KAFKA_BROKERS)
        put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
        put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
        put(ProducerConfig.RETRIES_CONFIG, "5") // Enable retries
    }.securityConfig(config)
    return KafkaProducer(producerConfig)
}

fun createKafkaProducerWithLongKey(config: NaisKafkaEnv): KafkaProducer<Long, String> {
    val producerConfig = Properties().apply {
        put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.KAFKA_BROKERS)
        put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, LongSerializer::class.java.name)
        put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
        put(ProducerConfig.RETRIES_CONFIG, "5") // Enable retries
    }.securityConfig(config)
    return KafkaProducer(producerConfig)
}

