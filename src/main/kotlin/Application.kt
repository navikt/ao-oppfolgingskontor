package no.nav

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import kotlinx.serialization.json.Json
import no.nav.db.configureDatabase
import no.nav.http.configureArbeidsoppfolgingskontorModule
import no.nav.http.graphql.configureGraphQlModule
import no.nav.kafka.KafkaStreamsPlugin

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.configureContentNegotiation() {
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            explicitNulls = false // Missing fields will be null
        })
    }
}

fun Application.module() {
    configureMonitoring()
    configureHTTP()
    configureSecurity()
    configureDatabase()
    install(KafkaStreamsPlugin)
    configureGraphQlModule()
    configureArbeidsoppfolgingskontorModule()
}
