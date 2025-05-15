package no.nav

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import kotlinx.serialization.json.Json
import no.nav.db.configureDatabase
import no.nav.http.client.Norg2Client
import no.nav.http.configureArbeidsoppfolgingskontorModule
import no.nav.http.graphql.configureGraphQlModule
import no.nav.http.graphql.getNorg2Url
import no.nav.kafka.KafkaStreamsPlugin
import no.nav.services.KontorNavnService
import no.nav.services.KontorTilhorighetService

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
    configureContentNegotiation()
    configureMonitoring()
    configureHTTP()
    configureSecurity()
    configureDatabase()
    install(KafkaStreamsPlugin)
    val norg2Client = Norg2Client(environment.getNorg2Url())
    val kontorNavnService = KontorNavnService(norg2Client)
    val kontorTilhorighetService = KontorTilhorighetService(kontorNavnService)
    configureGraphQlModule(norg2Client, kontorTilhorighetService)
    configureArbeidsoppfolgingskontorModule(kontorNavnService, KontorTilhorighetService(kontorNavnService))
}
