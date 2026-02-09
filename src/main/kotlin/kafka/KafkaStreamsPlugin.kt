package no.nav.kafka

import dab.poao.nav.no.health.CriticalErrorNotificationFunction
import http.client.VeilarbArenaClient
import io.ktor.events.EventDefinition
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationPlugin
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.MonitoringEvent
import io.ktor.server.application.log
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.kafka.KafkaStreamsMetrics
import kafka.consumers.`ArenakontorVedOppfolgingStartetProcessor`
import kafka.consumers.IdentChangeProcessor
import kafka.consumers.OppfolgingsHendelseProcessor
import kafka.consumers.PubliserKontorTilordningProcessor
import kafka.producers.KontorEndringProducer
import java.time.Duration
import net.javacrumbs.shedlock.provider.exposed.ExposedLockProvider
import no.nav.db.AktorId
import no.nav.getBrukAoRuting
import no.nav.getPubliserArenaKontor
import no.nav.http.client.IdentResult
import no.nav.isProduction
import no.nav.kafka.config.kafkaStreamsProps
import no.nav.kafka.config.configureTopology
import no.nav.kafka.consumers.EndringPaOppfolgingsBrukerProcessor
import no.nav.kafka.consumers.LeesahProcessor
import no.nav.kafka.consumers.KontortilordningsProcessor
import no.nav.kafka.consumers.SkjermingProcessor
import no.nav.services.AutomatiskKontorRutingService
import no.nav.services.KontorTilhorighetService
import no.nav.services.KontorTilordningService
import no.nav.services.OppfolgingsperiodeDao
import org.apache.kafka.streams.KafkaStreams
import org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler
import org.jetbrains.exposed.sql.Database
import org.slf4j.LoggerFactory
import services.IdentService
import services.OppfolgingsperiodeService
import java.util.concurrent.atomic.AtomicInteger

val KafkaStreamsStarting: EventDefinition<Application> = EventDefinition()
val KafkaStreamsStarted: EventDefinition<Application> = EventDefinition()
val KafkaStreamsStopping: EventDefinition<Application> = EventDefinition()
val KafkaStreamsStopped: EventDefinition<Application> = EventDefinition()

val shutDownTimeout = Duration.ofSeconds(60)
val logger = LoggerFactory.getLogger("no.nav.kafka.KafkaStreamsPlugin")

class KafkaStreamsPluginConfig(
    var automatiskKontorRutingService: AutomatiskKontorRutingService? = null,
    var fnrProvider: (suspend (ident: AktorId) -> IdentResult)? = null,
    var database: Database? = null,
    var meterRegistry: MeterRegistry? = null,
    var oppfolgingsperiodeService: OppfolgingsperiodeService? = null,
    var oppfolgingsperiodeDao: OppfolgingsperiodeDao? = null,
    var identService: IdentService? = null,
    var criticalErrorNotificationFunction: CriticalErrorNotificationFunction? = null,
    var kontorTilhorighetService: KontorTilhorighetService? = null,
    var kontorEndringProducer: KontorEndringProducer? = null,
    var veilarbArenaClient: VeilarbArenaClient? = null,
    var kontorTilordningService: KontorTilordningService? = null,
    var brukAoRuting: Boolean? = null,
)

const val arbeidsoppfolgingkontorSinkName = "endring-pa-arbeidsoppfolgingskontor"

val KafkaStreamsPlugin: ApplicationPlugin<KafkaStreamsPluginConfig> = createApplicationPlugin("KafkaStreams", ::KafkaStreamsPluginConfig) {
    val database = requireNotNull(this.pluginConfig.database) {
        "DataSource must be configured for KafkaStreamsPlugin"
    }
    val fnrProvider = requireNotNull(this.pluginConfig.fnrProvider) {
        "fnrProvider must be configured for KafkaStreamPlugin"
    }
    val automatiskKontorRutingService = requireNotNull(this.pluginConfig.automatiskKontorRutingService) {
        "AutomatiskKontorRutingService must be configured for KafkaStreamPlugin"
    }
    val oppfolgingsperiodeDao = requireNotNull(this.pluginConfig.oppfolgingsperiodeDao) {
        "OppfolgingsperiodeDao must be configured for KafkaStreamPlugin"
    }
    val oppfolgingsperiodeService = requireNotNull(this.pluginConfig.oppfolgingsperiodeService) {
        "OppfolgingsperiodeService must be configured for KafkaStreamPlugin"
    }
    val meterRegistry = requireNotNull(this.pluginConfig.meterRegistry) {
        "MeterRegistry must be configured for KafkaStreamPlugin"
    }
    val identService = requireNotNull(this.pluginConfig.identService) {
        "IdentService must be configured for KafkaStreamPlugin"
    }
    val setHasCriticalError = requireNotNull(this.pluginConfig.criticalErrorNotificationFunction) {
        "Must provide hasError function to KafkaStreamsPlugin"
    }
    val kontorTilhorighetService = requireNotNull(this.pluginConfig.kontorTilhorighetService) {
        "KontorTilhorighetService must be configured for KafkaStreamsPlugin"
    }
    val kontorProducer = requireNotNull(this.pluginConfig.kontorEndringProducer) {
        "KontorTilhorighetService must be configured for KafkaStreamsPlugin"
    }
    val veilarbArenaClient = requireNotNull(this.pluginConfig.veilarbArenaClient) {
        "VeilarbArenaClient must be configured for KafkaStreamPlugin"
    }
    val kontorTilordningService = requireNotNull(this.pluginConfig.kontorTilordningService) {
        "KontorTilordningService must be configured for KafkaStreamPlugin"
    }
    val brukAoRuting = requireNotNull(this.pluginConfig.brukAoRuting) {
        "BrukAoRuting must be configured for KafkaStreamPlugin"
    }

    val isProduction = environment.isProduction()
    if (isProduction) logger.info("Kjører i produksjonsmodus. Konsumerer kun siste-oppfølgingsperiode.")

    val endringPaOppfolgingsBrukerProcessor = EndringPaOppfolgingsBrukerProcessor(
        { oppfolgingsperiodeService.getCurrentOppfolgingsperiode(it) },
        { kontorTilhorighetService.getArenaKontorMedOppfolgingsperiode(it) },
        { kontorTilordningService.tilordneKontor(it, brukAoRuting) },
        { kontorProducer.publiserEndringPåKontor(it) },
        environment.getPubliserArenaKontor()
    )

    val kontorTilordningsProcessor = KontortilordningsProcessor(
        automatiskKontorRutingService,
        kontorTilordningService,
        // Hopp over personer som ikke finnes alder på i nytt felt i dev
        skipPersonIkkeFunnet = !isProduction,
        brukAoRuting
    )
    val leesahProcessor = LeesahProcessor(automatiskKontorRutingService, kontorTilordningService, fnrProvider, isProduction, brukAoRuting)
    val skjermingProcessor = SkjermingProcessor(automatiskKontorRutingService, kontorTilordningService, brukAoRuting)
    val identEndringProcessor = IdentChangeProcessor(identService)
    val oppfolgingsHendelseProcessor = OppfolgingsHendelseProcessor(
        oppfolgingsperiodeService,
        { periode -> kontorProducer.publiserTombstone(periode) },
    )
    val publiserKontorTilordningProcessor = PubliserKontorTilordningProcessor(
        identService::hentAlleIdenter,
        { kontorProducer.publiserEndringPåKontor(it) },
        environment.getBrukAoRuting(),
    )
    val arenakontorProcessor = ArenakontorVedOppfolgingStartetProcessor(
        veilarbArenaClient::hentArenaKontor,
        { kontorTilordningService.tilordneKontor(it, brukAoRuting) },
        { kontorTilhorighetService.getArenaKontorMedOppfolgingsperiode(it) },
        { kontorProducer.publiserEndringPåKontor(it) },
        environment.getPubliserArenaKontor(),
    )


    val topology = configureTopology(
        environment,
        ExposedLockProvider(database),
        kontortilordningsProcessor = kontorTilordningsProcessor,
        leesahProcessor = leesahProcessor,
        skjermingProcessor = skjermingProcessor,
        endringPaOppfolgingsBrukerProcessor = endringPaOppfolgingsBrukerProcessor,
        identEndringsProcessor = identEndringProcessor,
        oppfolgingsHendelseProcessor = oppfolgingsHendelseProcessor,
        publiserKontorTilordningProcessor = publiserKontorTilordningProcessor,
        arenakontorProcessor = arenakontorProcessor,
    )
    val kafkaStream = KafkaStreams(topology, kafkaStreamsProps(environment.config))

    kafkaStream.setUncaughtExceptionHandler {
        logger.error("Uncaught exception in Kafka Streams. Shutting down client", it)
        setHasCriticalError()
        StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.SHUTDOWN_CLIENT
    }

    val applicationId = environment.config.property("kafka.application-id").getString()
    val kafkaStreamsApplicationStateInteger = configureStateListenerMetrics(applicationId, kafkaStream, meterRegistry)
    val kafkaStreamsMetrics = KafkaStreamsMetrics(kafkaStream)
    kafkaStreamsMetrics.bindTo(meterRegistry)

    on(MonitoringEvent(ApplicationStarted)) { application ->
        application.log.info("Starter Kafka Streams")
        application.monitor.raise(KafkaStreamsStarting, application)
        kafkaStream.start()
        application.monitor.raise(KafkaStreamsStarted, application)
    }

    on(MonitoringEvent(ApplicationStopping)) { application ->
        application.log.info("Stopper Kafka Streams")
        kafkaStreamsApplicationStateInteger.set(0)
        application.monitor.raise(KafkaStreamsStopping, application)
        kafkaStream.close(shutDownTimeout)
        application.monitor.raise(KafkaStreamsStopped, application)
    }
}


private fun configureStateListenerMetrics(
    applicationId: String,
    kafkaStream: KafkaStreams,
    meterRegistry: MeterRegistry
): AtomicInteger {
    // 0=STOPPED/ERROR, 1=RUNNING, 2=REBALANCING
    val kafkaStateGaugeValue = AtomicInteger(0)
    Gauge.builder("kafka_streams_application_state", kafkaStateGaugeValue::get)
        .description("Current state of the Kafka Streams client (0=STOPPED/ERROR, 1=RUNNING, 2=REBALANCING)")
        .tag("streams_application_id", applicationId)
        .register(meterRegistry)

    kafkaStream.setStateListener { newState, _ ->
        when (newState) {
            KafkaStreams.State.RUNNING -> {
                logger.info("Setting kafka_streams_application_state gause to RUNNING")
                kafkaStateGaugeValue.set(1)
            }
            KafkaStreams.State.REBALANCING -> {
                logger.warn("Setting kafka_streams_application_state to REBALANCING")
                kafkaStateGaugeValue.set(2)
            }
            else -> {
                logger.error("Setting kafka_streams_application_state to ${newState.name}")
                kafkaStateGaugeValue.set(0)
            } // Dekker ERROR, NOT_RUNNING, PENDING_SHUTDOWN
        }
    }

    return kafkaStateGaugeValue
}
