package no.nav

import io.ktor.server.application.*
import no.nav.db.FlywayPlugin
import no.nav.kafka.KafkaAuthenticationConfig
import no.nav.kafka.KafkaStreamsPlugin
import no.nav.kafka.startKafkaStreams

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.module() {
    configureSerialization()
    configureDatabases()
    configureMonitoring()
    configureHTTP()
    configureSecurity()
    configureRouting()
    install(FlywayPlugin)

    val config = KafkaAuthenticationConfig(
        truststorePath = "path/to/truststore.jks",
        keystorePath = "path/to/keystore.p12",
        credstorePassword = "password"
    )

    install(KafkaStreamsPlugin) {
        kafkaStreams = listOf(
            startKafkaStreams("loltopic", config) { record -> println(record.value()) }
        )
    }
}
