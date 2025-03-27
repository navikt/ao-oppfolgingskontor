package no.nav

import io.ktor.server.application.*
import io.ktor.server.engine.*
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
    install(KafkaStreamsPlugin)
}
