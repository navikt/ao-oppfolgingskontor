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
import no.nav.http.configureFinnKontorModule
import no.nav.http.configureArbeidsoppfolgingskontorModule
import no.nav.http.graphql.*
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
    val (datasource, database) = configureDatabase()
    val meterRegistry = configureMonitoring()
    val setCriticalError: CriticalErrorNotificationFunction = configureHealthAndCompression()
    configureSecurity()


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
    val bigQueryClient = BigQueryClient(
        environment.config.property("app.gcp.projectId").getString(),
        ExposedLockProvider(database)
    )

    installBigQueryDailyScheduler(database, bigQueryClient = bigQueryClient)
    val kontorTilordningService = KontorTilordningService(bigQueryClient)

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
    val kontorTilhorighetService = KontorTilhorighetService(kontorNavnService, identService::hentAlleIdenter)
    val oppfolgingsperiodeService = OppfolgingsperiodeService(identService::hentAlleIdenter, kontorTilordningService::slettArbeidsoppfølgingskontorTilordning)
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
                oppfolgingStartDato = ZonedDateTime.now().minusMinutes(1),
                null
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
    val arenaSyncService = ArenaSyncService(veilarbArenaClient, kontorTilordningService, kontorTilhorighetService, oppfolgingsperiodeService, environment.getBrukAoRuting())
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
        this.kontorTilordningService = kontorTilordningService
        this.brukAoRuting = brukAoRuting
    }

    val issuer = environment.getIssuer()
    val authenticateRequest: AuthenticateRequest = { req -> req.call.authenticateCall(issuer) }
    configureGraphQlModule(
        norg2Client,
        kontorTilhorighetService,
        authenticateRequest,
        identService::hentAlleIdenter,
        poaoTilgangHttpClient::harLeseTilgang,
    )
    configureContentNegotiation()
    configureArbeidsoppfolgingskontorModule(
        kontorNavnService,
        kontorTilhorighetService,
        poaoTilgangHttpClient,
        oppfolgingsperiodeService,
        kontorTilordningService,
        { kontorEndringProducer.publiserEndringPåKontor(it) },
        hentSkjerming = { skjermingsClient.hentSkjerming(it) },
        hentAdresseBeskyttelse = { pdlClient.harStrengtFortroligAdresse(it) },
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

fun Application.installBigQueryDailyScheduler(database: Database, bigQueryClient: BigQueryClient) {
    environment.monitor.subscribe(ApplicationStarted) {
        launch {

            while (currentCoroutineContext().isActive) {
                val ventetid = beregnVentetid(23, 45) // Beregn hvor mange millisekunder til neste kjøring

                val totalSeconds = ventetid / 1000
                val hours = totalSeconds / 3600
                val minutes = (totalSeconds % 3600) / 60
                val seconds = totalSeconds % 60

                log.info("Neste BigQuery-jobb kjører om $hours timer, $minutes minutter og $seconds sekunder")
                delay(ventetid)

                withContext(Dispatchers.IO) {
                    log.info("Starter BigQuery-jobb for antall 2990 AO-kontor")
                    bigQueryClient.antall2990Kontor(database)
                    log.info("BigQuery-jobb ferdig")
                }
            }
        }
    }
}

/**
 * Beregner ventetid fra nå til neste kjøring på gitt klokkeslett (time + minutt).
 *
 * Eksempel: Hvis nå er 13:45, ønsket kjøring 13:57 → ventetid = 12 minutter.
 * Hvis ønsket tidspunkt allerede har passert, settes det til samme klokkeslett neste dag
 *
 * @param klokkeTime ønsket time for kjøring (0-23)
 * @param klokkeMinutt ønsket minutt for kjøring (0-59)
 * @return ventetid i millisekunder
 */
fun beregnVentetid(klokkeTime: Int, klokkeMinutt: Int): Long {
    val now = ZonedDateTime.now(ZoneId.of("Europe/Oslo"))
    var nextRun = now
        .withHour(klokkeTime)
        .withMinute(klokkeMinutt)
        .withSecond(0)
        .withNano(0)

    // Hvis ønsket tidspunkt allerede har passert, sett til samme tid neste dag
    if (!nextRun.isAfter(now)) {
        nextRun = nextRun.plusDays(1)
    }

    return Duration.between(now, nextRun).toMillis()
}
