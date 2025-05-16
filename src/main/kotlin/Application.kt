package no.nav

import io.ktor.server.application.*
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

fun Application.module() {
    configureMonitoring()
    configureHealthAndCompression()
    configureSecurity()
    configureDatabase()
    install(KafkaStreamsPlugin)
    val norg2Client = Norg2Client(environment.getNorg2Url())
    val kontorNavnService = KontorNavnService(norg2Client)
    val kontorTilhorighetService = KontorTilhorighetService(kontorNavnService)
    configureGraphQlModule(norg2Client, kontorTilhorighetService)
    configureArbeidsoppfolgingskontorModule(kontorNavnService, KontorTilhorighetService(kontorNavnService))
}
