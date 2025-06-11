package no.nav

import io.ktor.server.application.*
import no.nav.db.configureDatabase
import no.nav.http.client.Norg2Client
import no.nav.http.client.PdlClient
import no.nav.http.client.PoaoTilgangKtorHttpClient
import no.nav.http.configureArbeidsoppfolgingskontorModule
import no.nav.http.graphql.configureGraphQlModule
import no.nav.http.graphql.getNorg2Url
import no.nav.http.graphql.getPDLUrl
import no.nav.http.graphql.getPoaoTilgangUrl
import no.nav.kafka.KafkaStreamsPlugin
import no.nav.services.AutomatiskKontorRutingService
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
    val norg2Client = Norg2Client(environment.getNorg2Url())
    val pdlClient = PdlClient(environment.getPDLUrl())
    val kontorNavnService = KontorNavnService(norg2Client)
    val kontorTilhorighetService = KontorTilhorighetService(kontorNavnService)
    val poaoTilgangHttpClient = PoaoTilgangKtorHttpClient(environment.getPoaoTilgangUrl())
    val automatiskKontorRutingService = AutomatiskKontorRutingService(
        { poaoTilgangHttpClient.hentTilgangsattributter(it) },
        { pdlClient.hentAlder(it) },
        { pdlClient.hentFnrFraAktorId(it) }
    )
    install(KafkaStreamsPlugin) {
        this.automatiskKontorRutingService = automatiskKontorRutingService
    }

    configureGraphQlModule(norg2Client, kontorTilhorighetService)
    configureArbeidsoppfolgingskontorModule(kontorNavnService, KontorTilhorighetService(kontorNavnService))
}
