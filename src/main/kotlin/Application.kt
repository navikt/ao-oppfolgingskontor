package no.nav

import dab.poao.nav.no.health.CriticalErrorNotificationFunction
import eventsLogger.BigQueryClient
import http.client.*
import http.configureContentNegotiation
import http.configureHentArbeidsoppfolgingskontorBulkModule
import io.ktor.server.application.*
import io.ktor.server.netty.*
import kafka.producers.KontorEndringProducer
import kotlinx.coroutines.*
import net.javacrumbs.shedlock.provider.exposed.ExposedLockProvider
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
import no.nav.http.configureAdminModule
import no.nav.http.configureArbeidsoppfolgingskontorModule
import no.nav.http.configureFinnKontorModule
import no.nav.http.graphql.*
import no.nav.http.log
import no.nav.kafka.KafkaStreamsPlugin
import no.nav.kafka.config.createKafkaProducer
import no.nav.kafka.config.toKafkaEnv
import no.nav.services.*
import org.jetbrains.exposed.sql.Database
import services.*
import topics
import utils.Outcome
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.*

fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.module() {
    val meterRegistry = configureMonitoring()
    val setCriticalError: CriticalErrorNotificationFunction = configureHealthAndCompression()
    configureSecurity()
    val (datasource, database) = configureDatabase()

    installBigQueryDailyScheduler(database)

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
    val simulerKontorTilordning: suspend (IdentSomKanLagres, Boolean) -> TilordningResultat =
        { ident, erArbeidssøker ->
            dryRunKontorRutingService.tilordneKontorAutomatisk(
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
    configureFinnKontorModule(simulerKontorTilordning, kontorNavnService::getKontorNavn)
    configureAdminModule(simulerKontorTilordning, republiseringService, arenaSyncService, identService)
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

fun Application.installBigQueryDailyScheduler(database: Database) {
    environment.monitor.subscribe(ApplicationStarted) {
        launch {
            val client = BigQueryClient(
                environment.config.property("app.gcp.projectId").getString(),
                ExposedLockProvider(database)
            )

            while (currentCoroutineContext().isActive) {
                val ventetid = beregnVentetid(13, 48) // f.eks. 13:35
                delay(ventetid)
                client.runDaily2990AoKontorJob(database)
                delay(Duration.ofDays(1).toMillis())
            }
        }
    }
}
fun beregnVentetid(klokkeTime: Int, klokkeMinutt: Int): Long {
    val now = ZonedDateTime.now(ZoneId.of("Europe/Oslo"))
    var nextRun = now.withHour(klokkeTime).withMinute(klokkeMinutt)
    if (nextRun.isBefore(now)) nextRun = nextRun.plusDays(1)
    return Duration.between(now, nextRun).toMillis()
}

