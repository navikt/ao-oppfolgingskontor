package no.nav.kafka

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
import java.time.Duration
import net.javacrumbs.shedlock.provider.exposed.ExposedLockProvider
import no.nav.http.client.FnrResult
import no.nav.http.client.PdlClient
import no.nav.kafka.config.AvroTopicConsumer
import no.nav.kafka.config.StringTopicConsumer
import no.nav.kafka.config.kafkaStreamsProps
import no.nav.kafka.config.configureTopology
import no.nav.kafka.consumers.EndringPaOppfolgingsBrukerConsumer
import no.nav.kafka.consumers.FnrEllerAktorIdEllerNpid
import no.nav.kafka.consumers.LeesahConsumer
import no.nav.kafka.consumers.OppfolgingsPeriodeConsumer
import no.nav.kafka.consumers.SkjermingConsumer
import no.nav.kafka.processor.LeesahAvroSerdes
import no.nav.services.AutomatiskKontorRutingService
import no.nav.services.OppfolgingsperiodeService
import org.apache.kafka.streams.KafkaStreams
import org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler
import org.jetbrains.exposed.sql.Database
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger

val KafkaStreamsStarting: EventDefinition<Application> = EventDefinition()
val KafkaStreamsStarted: EventDefinition<Application> = EventDefinition()
val KafkaStreamsStopping: EventDefinition<Application> = EventDefinition()
val KafkaStreamsStopped: EventDefinition<Application> = EventDefinition()

val shutDownTimeout = Duration.ofSeconds(1)
val logger = LoggerFactory.getLogger("no.nav.kafka.KafkaStreamsPlugin")

class KafkaStreamsPluginConfig(
        var automatiskKontorRutingService: AutomatiskKontorRutingService? = null,
        var fnrProvider: (suspend (fnrEllerAktorIdEllerNpid: FnrEllerAktorIdEllerNpid) -> FnrResult)? = null,
        var database: Database? = null,
        var meterRegistry: MeterRegistry? = null,
        var oppfolgingsperiodeService: OppfolgingsperiodeService? = null,
        var pdlClient: PdlClient? = null
)

val KafkaStreamsPlugin: ApplicationPlugin<KafkaStreamsPluginConfig> = createApplicationPlugin("KafkaStreams", ::KafkaStreamsPluginConfig) {
        val database =
                requireNotNull(this.pluginConfig.database) {
                    "DataSource must be configured for KafkaStreamsPlugin"
                }
        val fnrProvider =
                requireNotNull(this.pluginConfig.fnrProvider) {
                    "fnrProvider must be configured for KafkaStreamPlugin"
                }
        val automatiskKontorRutingService =
                requireNotNull(this.pluginConfig.automatiskKontorRutingService) {
                    "AutomatiskKontorRutingService must be configured for KafkaStreamPlugin"
                }
        val oppfolgingsperiodeService =
                requireNotNull(this.pluginConfig.oppfolgingsperiodeService) {
                    "OppfolgingsperiodeService must be configured for KafkaStreamPlugin"
                }
        val pdlClient =
                requireNotNull(this.pluginConfig.pdlClient) {
                    "PdlClient must be configured for KafkaStreamPlugin"
                }

    val lockProvider = ExposedLockProvider(database)

    val endringPaOppfolgingsBrukerConsumer = EndringPaOppfolgingsBrukerConsumer()
    val oppfolgingsBrukerTopic = environment.config.property("topics.inn.endringPaOppfolgingsbruker").getString()

    val oppfolgingsPeriodeConsumer = OppfolgingsPeriodeConsumer(
        automatiskKontorRutingService,
        oppfolgingsperiodeService,
        { aktorId -> pdlClient.hentFnrFraAktorId(aktorId) }
    )
    val oppfolgingsPeriodeTopic = environment.config.property("topics.inn.oppfolgingsperiodeV1").getString()

    val leesahConsumer = LeesahConsumer(automatiskKontorRutingService, fnrProvider)
    val leesahTopic = environment.config.property("topics.inn.pdlLeesah").getString()
    val spesificAvroValueSerde = LeesahAvroSerdes(environment.config).valueAvroSerde
    val specificAvroKeySerde = LeesahAvroSerdes(environment.config).keyAvroSerde

    val skjermingConsumer = SkjermingConsumer(automatiskKontorRutingService)
    val skjermingTopic = environment.config.property("topics.inn.skjerming").getString()

    val topology = configureTopology(listOf(
        StringTopicConsumer(
            oppfolgingsBrukerTopic,
            { record, maybeRecordMetadata -> endringPaOppfolgingsBrukerConsumer.consume(record, maybeRecordMetadata) }
        ),
        StringTopicConsumer(
            oppfolgingsPeriodeTopic,
            { record, maybeRecordMetadata -> oppfolgingsPeriodeConsumer.consume(record, maybeRecordMetadata) }
        ),
        AvroTopicConsumer(
            leesahTopic, leesahConsumer::consume, spesificAvroValueSerde, specificAvroKeySerde
        ),
        StringTopicConsumer(
            skjermingTopic,
            { record, maybeRecordMetadata -> skjermingConsumer.consume(record, maybeRecordMetadata) }
        )),
        lockProvider
    )
    val kafkaStream = KafkaStreams(topology, kafkaStreamsProps(environment.config))

    kafkaStream.setUncaughtExceptionHandler {
        logger.error("Uncaught exception in Kafka Streams. Shutting down client", it)
        StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.SHUTDOWN_CLIENT
    }
    if (this.pluginConfig.meterRegistry != null) {
        val applicationId = environment.config.property("kafka.application-id").getString()
        configureStateListenerMetrics(applicationId, kafkaStream, this.pluginConfig.meterRegistry as MeterRegistry)
        val kafkaStreamsMetrics = KafkaStreamsMetrics(kafkaStream)
        kafkaStreamsMetrics.bindTo(this.pluginConfig.meterRegistry as MeterRegistry)
    }

    on(MonitoringEvent(ApplicationStarted)) { application ->
        application.log.info("Starter Kafka Streams")
        application.monitor.raise(KafkaStreamsStarting, application)
        kafkaStream.start()
        application.monitor.raise(KafkaStreamsStarted, application)
    }

    on(MonitoringEvent(ApplicationStopping)) { application ->
        application.log.info("Stopper Kafka Streams")
        application.monitor.raise(KafkaStreamsStopping, application)
        kafkaStream.close(shutDownTimeout)
        application.monitor.raise(KafkaStreamsStopped, application)
    }
}


private fun configureStateListenerMetrics(
    applicationId: String,
    kafkaStream: KafkaStreams,
    meterRegistry: MeterRegistry
) {
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
                logger.error("Setting kafka_streams_application_state to STOPPED/ERROR")
                kafkaStateGaugeValue.set(0)
            } // Dekker ERROR, NOT_RUNNING, PENDING_SHUTDOWN
        }
    }
}
