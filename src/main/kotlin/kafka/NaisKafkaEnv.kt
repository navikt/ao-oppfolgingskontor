package no.nav.kafka

import io.ktor.server.config.ApplicationConfig

data class NaisKafkaEnv(
    val KAFKA_BROKERS: String, // Comma-separated list of HOST:PORT pairs to Kafka brokers
    val KAFKA_SCHEMA_REGISTRY: String, // URL to schema registry
    val KAFKA_SCHEMA_REGISTRY_USER: String, // Username to use with schema registry
    val KAFKA_SCHEMA_REGISTRY_PASSWORD: String, // Password to use with schema registry
    val KAFKA_CERTIFICATE: String, // Client certificate for connecting to the Kafka brokers, as string data
    val KAFKA_CERTIFICATE_PATH: String, // Client certificate for connecting to the Kafka brokers, as file
    val KAFKA_PRIVATE_KEY: String, // Client certificate key for connecting to the Kafka brokers, as string data
    val KAFKA_PRIVATE_KEY_PATH: String, // Client certificate key for connecting to the Kafka brokers, as file
    val KAFKA_CA: String, // Certificate authority used to validate the Kafka brokers, as string data
    val KAFKA_CA_PATH: String, // Certificate authority used to validate the Kafka brokers, as file
    val KAFKA_CREDSTORE_PASSWORD: String, // Password needed to use the keystore and truststore
    val KAFKA_KEYSTORE_PATH: String, // PKCS#12 keystore for use with Java clients, as file
    val KAFKA_TRUSTSTORE_PATH: String, // JKS truststore for use with Java clients, as file
    val AIVEN_SECRET_UPDATED: String, // A timestamp of when the secret was created
)

fun ApplicationConfig.toKafkaEnv() {
    NaisKafkaEnv(
        KAFKA_BROKERS = property("kafka.brokers").getString(),
        KAFKA_SCHEMA_REGISTRY = property("kafka.schema-registry").getString(),
        KAFKA_SCHEMA_REGISTRY_USER = property("kafka.schema-registry-user").getString(),
        KAFKA_SCHEMA_REGISTRY_PASSWORD = property("kafka.schema-registry-password").getString(),
        KAFKA_CERTIFICATE = property("kafka.certificate").getString(),
        KAFKA_CERTIFICATE_PATH = property("kafka.certificate-path").getString(),
        KAFKA_PRIVATE_KEY = property("kafka.private-key").getString(),
        KAFKA_PRIVATE_KEY_PATH = property("kafka.private-key-path").getString(),
        KAFKA_CA = property("kafka.ca").getString(),
        KAFKA_CA_PATH = property("kafka.ca-path").getString(),
        KAFKA_CREDSTORE_PASSWORD = property("kafka.credstore-password").getString(),
        KAFKA_KEYSTORE_PATH = property("kafka.keystore-path").getString(),
        KAFKA_TRUSTSTORE_PATH = property("kafka.truststore-path").getString(),
        AIVEN_SECRET_UPDATED = property("aiven.secret-updated").getString(),
    )
}