package no.nav.kafka

data class KafkaAuthenticationConfig(
    val truststorePath: String,
    val keystorePath: String,
    val credstorePassword: String
)