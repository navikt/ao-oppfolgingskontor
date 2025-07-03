package no.nav

import io.ktor.server.application.*
import io.ktor.server.netty.*
import no.nav.db.configureDatabase
import no.nav.http.client.*
import no.nav.http.client.arbeidssogerregisteret.ArbeidssokerregisterClient
import no.nav.http.client.arbeidssogerregisteret.getArbeidssokerregisteretScope
import no.nav.http.client.arbeidssogerregisteret.getArbeidssokerregisteretUrl
import no.nav.http.client.tokenexchange.TexasSystemTokenClient
import no.nav.http.client.tokenexchange.getNaisTokenEndpoint
import no.nav.http.configureArbeidsoppfolgingskontorModule
import no.nav.http.graphql.configureGraphQlModule
import no.nav.http.graphql.getNorg2Url
import no.nav.http.graphql.getPDLUrl
import no.nav.kafka.KafkaStreamsPlugin
import no.nav.services.AutomatiskKontorRutingService
import no.nav.services.GTNorgService
import no.nav.services.KontorNavnService
import no.nav.services.KontorTilhorighetService

fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.module() {
    configureMonitoring()
    configureHealthAndCompression()
    configureSecurity()
    val database = configureDatabase()

    val norg2Client = Norg2Client(environment.getNorg2Url())

    val texasClient = TexasSystemTokenClient(environment.getNaisTokenEndpoint())
    val pdlClient = PdlClient(environment.getPDLUrl(), texasClient.tokenProvider(environment.getPdlScope()))
    val arbeidssokerregisterClient = ArbeidssokerregisterClient(
        environment.getArbeidssokerregisteretUrl(),
        texasClient.tokenProvider(environment.getArbeidssokerregisteretScope()))
    /*
    val poaoTilgangHttpClient = PoaoTilgangKtorHttpClient(
        environment.getPoaoTilgangUrl(),
        texasClient.tokenProvider(environment.getPoaoTilgangScope())
    )*/

    val gtNorgService = GTNorgService(
        { pdlClient.hentGt(it) },
        { norg2Client.hentKontorForGt(it) }
    )
    val kontorNavnService = KontorNavnService(norg2Client)
    val kontorTilhorighetService = KontorTilhorighetService(kontorNavnService)
    val automatiskKontorRutingService = AutomatiskKontorRutingService(
        { gtNorgService.hentGtKontorForBruker(it) },
        { pdlClient.hentAlder(it) },
        { pdlClient.hentFnrFraAktorId(it) },
        { arbeidssokerregisterClient.hentProfilering(it) }
    )

    install(KafkaStreamsPlugin) {
        this.automatiskKontorRutingService = automatiskKontorRutingService
        this.fnrProvider = pdlClient::hentFnrFraAktorId
        this.database = database
    }

    configureGraphQlModule(norg2Client, kontorTilhorighetService)
    configureArbeidsoppfolgingskontorModule(kontorNavnService, KontorTilhorighetService(kontorNavnService))
}
