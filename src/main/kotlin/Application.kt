package no.nav

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import no.nav.db.configureDatabase
import no.nav.http.configureArbeidsoppfolgingskontorModule
import no.nav.http.graphql.configureGraphQlModule
import no.nav.kafka.KafkaStreamsPlugin
import no.nav.kafka.exceptionHandler.KafkaStreamsTaskMonitor

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.configureContentNegotiation() {
    install(ContentNegotiation) {
        json()
    }
}

fun Application.module() {
    val meterRegistry = configureMonitoring()
    configureHTTP()
    configureSecurity()
    configureDatabase()
    install(KafkaStreamsPlugin) {
        monitor = KafkaStreamsTaskMonitor(environment.config.property("kafka.application-id").getString(), meterRegistry)
    }
    configureGraphQlModule()
    configureArbeidsoppfolgingskontorModule()
}
