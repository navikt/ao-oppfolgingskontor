package no.nav

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import no.nav.db.FlywayPlugin
import no.nav.db.PostgresDataSource
import no.nav.db.configureDatabase
import no.nav.http.configureArbeidsoppfolgingskontorModule
import no.nav.http.graphql.configureGraphQlModule
import no.nav.kafka.KafkaStreamsPlugin
import org.jetbrains.exposed.sql.Database

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.configureContentNegotiation() {
    install(ContentNegotiation) {
        json()
    }
}

fun Application.module() {
    configureMonitoring()
    configureHTTP()
    configureSecurity()
    configureRouting()
    configureDatabase()
    install(KafkaStreamsPlugin)
    configureGraphQlModule()
    configureArbeidsoppfolgingskontorModule()
}
