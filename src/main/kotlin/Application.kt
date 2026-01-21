package no.nav

import dab.poao.nav.no.health.CriticalErrorNotificationFunction
import http.client.AaregClient
import http.client.EregClient
import http.client.VeilarbArenaClient
import http.client.getAaregScope
import http.client.getVeilarbarenaScope
import http.configureContentNegotiation
import http.configureHentArbeidsoppfolgingskontorBulkModule
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationEnvironment
import io.ktor.server.application.install
import io.ktor.server.netty.*
import kafka.producers.KontorEndringProducer
import no.nav.db.IdentSomKanLagres
import no.nav.db.configureDatabase
import no.nav.domain.OppfolgingsperiodeId
import no.nav.http.client.*
import no.nav.http.client.arbeidssogerregisteret.ArbeidssokerregisterClient
import no.nav.http.client.arbeidssogerregisteret.getArbeidssokerregisteretScope
import no.nav.http.client.arbeidssogerregisteret.getArbeidssokerregisteretUrl
import no.nav.http.client.poaoTilgang.PoaoTilgangKtorHttpClient
import no.nav.http.client.poaoTilgang.getPoaoTilgangScope
import no.nav.http.client.tokenexchange.TexasSystemTokenClient
import no.nav.http.client.tokenexchange.getNaisTokenEndpoint
import no.nav.http.configureArbeidsoppfolgingskontorModule
import no.nav.http.configureAdminModule
import no.nav.http.configureFinnKontorModule
import no.nav.http.graphql.AuthenticateRequest
import no.nav.http.graphql.configureGraphQlModule
import no.nav.http.graphql.getAaregUrl
import no.nav.http.graphql.getEregUrl
import no.nav.http.graphql.getNorg2Url
import no.nav.http.graphql.getPDLUrl
import no.nav.http.graphql.getPoaoTilgangUrl
import no.nav.http.graphql.getVeilarbArenaUrl
import no.nav.kafka.KafkaStreamsPlugin
import no.nav.kafka.config.createKafkaProducer
import no.nav.kafka.config.toKafkaEnv
import no.nav.services.AutomatiskKontorRutingService
import no.nav.services.GTNorgService
import no.nav.services.KontorNavnService
import no.nav.services.KontorTilhorighetService
import no.nav.services.KontorTilordningService
import no.nav.services.OppfolgingsperiodeDao
import no.nav.services.TilordningResultat
import services.ArenaSyncService
import services.IdentService
import services.KontorForBrukerMedMangelfullGtService
import services.KontorRepubliseringService
import services.KontorTilhorighetBulkService
import services.OppfolgingsperiodeService
import topics
import utils.Outcome
import java.time.ZonedDateTime
import java.util.UUID

fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.module() {
    val meterRegistry = configureMonitoring()
    val setCriticalError: CriticalErrorNotificationFunction = configureHealthAndCompression()
    configureSecurity()
    val (datasource, database) = configureDatabase()

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
    val veilarbArenaClient = VeilarbArenaClient(
        baseUrl = environment.getVeilarbArenaUrl(),
        azureTokenProvider = texasClient.tokenProvider(environment.getVeilarbarenaScope())
    )
    val aaregClient = AaregClient(
        baseUrl = environment.getAaregUrl(),
        azureTokenProvider = texasClient.tokenProvider(environment.getAaregScope())
    )
    val eregClient = EregClient(
        baseUrl = environment.getEregUrl(),
    )
 
    val kontorForBrukerMedMangelfullGtService = KontorForBrukerMedMangelfullGtService(
        {aaregClient.hentArbeidsforhold(it)},
        {eregClient.hentNøkkelinfoOmArbeidsgiver(it)},
        {gt, strengtFortroligAdresse, skjermet -> norg2Client.hentKontorForGt(gt,strengtFortroligAdresse, skjermet)},
        pdlClient::sokAdresseFritekst
    )
    val identService = IdentService({ pdlClient.hentIdenterFor(it) })
    val gtNorgService = GTNorgService(
        { pdlClient.hentGt(it) },
        { gt, strengtFortroligAdresse, skjermet -> norg2Client.hentKontorForGt(gt, strengtFortroligAdresse, skjermet) },
        { gt, strengtFortroligAdresse, skjermet -> norg2Client.hentKontorForBrukerMedMangelfullGT(gt, strengtFortroligAdresse, skjermet) },
        { ident, gt, strengtFortroligAdresse, skjermet -> kontorForBrukerMedMangelfullGtService.finnKontorForGtBasertPåArbeidsforhold(ident,gt, strengtFortroligAdresse, skjermet) }
    )
    val kontorNavnService = KontorNavnService(norg2Client)
    val kontorTilhorighetService = KontorTilhorighetService(kontorNavnService, poaoTilgangHttpClient, identService::hentAlleIdenter)
    val oppfolgingsperiodeService = OppfolgingsperiodeService(identService::hentAlleIdenter)
    val automatiskKontorRutingService = AutomatiskKontorRutingService(
        { fnr, strengtFortroligAdresse, skjermet -> gtNorgService.hentGtKontorForBruker(fnr, strengtFortroligAdresse, skjermet) },
        { pdlClient.hentAlder(it) },
        { arbeidssokerregisterClient.hentProfilering(it) },
        { skjermingsClient.hentSkjerming(it) },
        { pdlClient.harStrengtFortroligAdresse(it) },
        { oppfolgingsperiodeService.getCurrentOppfolgingsperiode(it) },
        { _, oppfolgingsperiodeId -> OppfolgingsperiodeDao.finnesAoKontorPåPeriode(oppfolgingsperiodeId) },
    )
    val dryRunKontorRutingService = automatiskKontorRutingService.copy(harAlleredeTilordnetAoKontorForOppfolgingsperiode = { Ident, b: OppfolgingsperiodeId -> Outcome.Success(false) })
    val dryRunTilordneKontor: suspend (IdentSomKanLagres, Boolean) -> TilordningResultat = { ident, erArbeidssøker -> dryRunKontorRutingService.tilordneKontorAutomatisk(
            ident = ident,
            oppfolgingsperiodeId = OppfolgingsperiodeId(UUID.randomUUID()),
            erArbeidssøkerRegistrering = erArbeidssøker,
            oppfolgingStartDato = ZonedDateTime.now().minusMinutes(1)
        )
    }

    val kontorEndringProducer = KontorEndringProducer(
        producer = createKafkaProducer(this.environment.config.toKafkaEnv()),
        kontorTopicNavn = this.environment.topics().ut.arbeidsoppfolgingskontortilordninger.name,
        kontorNavnProvider = { kontorId -> kontorNavnService.getKontorNavn(kontorId) },
        hentAlleIdenter = { identSomKanLagres -> identService.hentAlleIdenter(identSomKanLagres) },
        brukAoRuting = this.environment.getBrukAoRuting()
    )
    val republiseringService = KontorRepubliseringService(kontorEndringProducer::republiserKontor, datasource, kontorNavnService::friskOppAlleKontorNavn)
    val arenaSyncService = ArenaSyncService(veilarbArenaClient, KontorTilordningService, kontorTilhorighetService, oppfolgingsperiodeService, environment.getBrukAoRuting())
    val brukAoRuting = environment.getBrukAoRuting()

    install(KafkaStreamsPlugin) {
        this.automatiskKontorRutingService = automatiskKontorRutingService
        this.fnrProvider = { ident ->  identService.veksleAktorIdIForetrukketIdent(ident) }
        this.database = database
        this.meterRegistry = meterRegistry
        this.oppfolgingsperiodeService = oppfolgingsperiodeService
        this.oppfolgingsperiodeDao = OppfolgingsperiodeDao
        this.identService = identService
        this.criticalErrorNotificationFunction = setCriticalError
        this.kontorTilhorighetService = kontorTilhorighetService
        this.kontorEndringProducer = kontorEndringProducer
        this.veilarbArenaClient = veilarbArenaClient
        this.brukAoRuting = brukAoRuting
    }

    val issuer = environment.getIssuer()
    val authenticateRequest: AuthenticateRequest = { req -> req.call.authenticateCall(issuer) }
    configureGraphQlModule(norg2Client, kontorTilhorighetService, authenticateRequest, identService::hentAlleIdenter)
    configureContentNegotiation()
    configureArbeidsoppfolgingskontorModule(
        kontorNavnService,
        kontorTilhorighetService,
        poaoTilgangHttpClient,
        oppfolgingsperiodeService,
        { kontorEndringProducer.publiserEndringPåKontor(it) },
        brukAoRuting = environment.getBrukAoRuting(),
    )
    configureFinnKontorModule(dryRunTilordneKontor, kontorNavnService::getKontorNavn)
    configureAdminModule(dryRunTilordneKontor, republiseringService, arenaSyncService, identService)
    configureHentArbeidsoppfolgingskontorBulkModule(KontorTilhorighetBulkService)
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

fun ApplicationEnvironment.getBrukAoRuting(): Boolean {
    return config.property("brukAoRuting").getString().toBoolean()
}

fun ApplicationEnvironment.getPubliserArenaKontor(): Boolean {
    return !getBrukAoRuting()
}