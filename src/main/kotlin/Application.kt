package no.nav

import io.ktor.server.application.*
import io.ktor.server.netty.*
import no.nav.db.configureDatabase
import no.nav.http.client.*
import no.nav.http.client.arbeidssogerregisteret.ArbeidssokerregisterClient
import no.nav.http.client.arbeidssogerregisteret.getArbeidssokerregisteretScope
import no.nav.http.client.arbeidssogerregisteret.getArbeidssokerregisteretUrl
import no.nav.http.client.poaoTilgang.PoaoTilgangKtorHttpClient
import no.nav.http.client.poaoTilgang.getPoaoTilgangScope
import no.nav.http.client.tokenexchange.TexasSystemTokenClient
import no.nav.http.client.tokenexchange.getNaisTokenEndpoint
import no.nav.http.configureArbeidsoppfolgingskontorModule
import no.nav.http.graphql.AuthenticateRequest
import no.nav.http.graphql.configureGraphQlModule
import no.nav.http.graphql.getNorg2Url
import no.nav.http.graphql.getPDLUrl
import no.nav.http.graphql.getPoaoTilgangUrl
import no.nav.kafka.KafkaStreamsPlugin
import no.nav.services.AutomatiskKontorRutingService
import no.nav.services.GTNorgService
import no.nav.services.KontorNavnService
import no.nav.services.KontorTilhorighetService
import no.nav.services.KontorTilordningService
import no.nav.services.OppfolgingsperiodeService
import services.IdentService

fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.module() {
    val meterRegistry = configureMonitoring()
    configureHealthAndCompression()
    configureSecurity()
    val database = configureDatabase()

    val norg2Client = Norg2Client(environment.getNorg2Url())

    val texasClient = TexasSystemTokenClient(environment.getNaisTokenEndpoint())
    val pdlClient = PdlClient(environment.getPDLUrl(), texasClient.tokenProvider(environment.getPdlScope()))
    val arbeidssokerregisterClient = ArbeidssokerregisterClient(
        environment.getArbeidssokerregisteretUrl(),
        texasClient.tokenProvider(environment.getArbeidssokerregisteretScope()))
    val skjermingsClient = SkjermingsClient(
        environment.getSkjermedePersonerUrl(),
        texasClient.tokenProvider(environment.getSkjermedePersonerScope())
    )
    val poaoTilgangHttpClient = PoaoTilgangKtorHttpClient(
        environment.getPoaoTilgangUrl(),
        texasClient.tokenProvider(environment.getPoaoTilgangScope())
    )

    val identService = IdentService({ pdlClient.hentIdenterFor(it) })
    val gtNorgService = GTNorgService(
        { pdlClient.hentGt(it) },
        { gt, strengtFortroligAdresse, skjermet -> norg2Client.hentKontorForGt(gt, strengtFortroligAdresse, skjermet) },
        { gt, strengtFortroligAdresse, skjermet -> norg2Client.hentKontorForBrukerMedMangelfullGT(gt, strengtFortroligAdresse, skjermet) },
    )
    val kontorNavnService = KontorNavnService(norg2Client)
    val kontorTilhorighetService = KontorTilhorighetService(kontorNavnService, poaoTilgangHttpClient)
    val automatiskKontorRutingService = AutomatiskKontorRutingService(
        KontorTilordningService::tilordneKontor,
        { fnr, strengtFortroligAdresse, skjermet -> gtNorgService.hentGtKontorForBruker(fnr, strengtFortroligAdresse, skjermet) },
        { pdlClient.hentAlder(it) },
        { arbeidssokerregisterClient.hentProfilering(it) },
        { skjermingsClient.hentSkjerming(it) },
        { pdlClient.harStrengtFortroligAdresse(it) },
        {  OppfolgingsperiodeService.getCurrentOppfolgingsperiode(it) }
    )

    install(KafkaStreamsPlugin) {
        this.automatiskKontorRutingService = automatiskKontorRutingService
        this.fnrProvider = { ident ->  identService.hentForetrukketIdentFor(ident) }
        this.database = database
        this.meterRegistry = meterRegistry
        this.oppfolgingsperiodeService = OppfolgingsperiodeService
    }

    val issuer = environment.getIssuer()
    val authenticateRequest: AuthenticateRequest = { req -> req.call.authenticateCall(issuer) }
    configureGraphQlModule(norg2Client, kontorTilhorighetService, authenticateRequest)
    configureArbeidsoppfolgingskontorModule(kontorNavnService, kontorTilhorighetService, poaoTilgangHttpClient)
}

fun ApplicationEnvironment.getIssuer(): String {
    val issuers = config.configList("no.nav.security.jwt.issuers")
    val firstIssuerConfig = issuers.first()
    return firstIssuerConfig.property("issuer_name").getString()
}

fun ApplicationEnvironment.isProduction(): Boolean {
    return config.propertyOrNull("cluster")
        ?.getString()
        ?.contentEquals("prod-gcp") ?: false
}
