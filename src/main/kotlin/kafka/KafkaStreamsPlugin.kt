package no.nav.kafka

import io.ktor.events.EventDefinition
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationPlugin
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.MonitoringEvent
import io.ktor.server.application.log
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.kafka.KafkaStreamsMetrics
import net.javacrumbs.shedlock.provider.exposed.ExposedLockProvider
import no.nav.http.client.FnrResult
import no.nav.kafka.config.AvroTopicConsumer
import no.nav.kafka.config.StringTopicConsumer
import no.nav.kafka.config.configureKafkaStreams
import no.nav.kafka.config.configureTopology
import no.nav.kafka.consumers.EndringPaOppfolgingsBrukerConsumer
import no.nav.kafka.consumers.FnrEllerAktorId
import no.nav.kafka.consumers.LeesahConsumer
import no.nav.kafka.consumers.OppfolgingsPeriodeConsumer
import no.nav.kafka.consumers.SkjermingConsumer
import no.nav.kafka.processor.LeesahAvroDeserializer
import no.nav.services.AutomatiskKontorRutingService
import org.apache.kafka.streams.KafkaStreams
import org.jetbrains.exposed.sql.Database
import java.time.Duration

val KafkaStreamsStarting: EventDefinition<Application> = EventDefinition()
val KafkaStreamsStarted: EventDefinition<Application> = EventDefinition()
val KafkaStreamsStopping: EventDefinition<Application> = EventDefinition()
val KafkaStreamsStopped: EventDefinition<Application> = EventDefinition()

val shutDownTimeout = Duration.ofSeconds(1)

class KafkaStreamsPluginConfig(
    var automatiskKontorRutingService: AutomatiskKontorRutingService? = null,
    var fnrProvider: (suspend (fnrEllerAktorId: FnrEllerAktorId) -> FnrResult)? = null,
    var database: Database? = null,
    var meterRegistry: MeterRegistry? = null
)

val KafkaStreamsPlugin: ApplicationPlugin<KafkaStreamsPluginConfig> =
    createApplicationPlugin("KafkaStreams", ::KafkaStreamsPluginConfig) {
        val database = requireNotNull(this.pluginConfig.database) { "DataSource must be configured for KafkaStreamsPlugin" }
        val fnrProvider = requireNotNull(this.pluginConfig.fnrProvider) { "fnrProvider must be configured for KafkaStreamPlugin" }
        val automatiskKontorRutingService = requireNotNull(this.pluginConfig.automatiskKontorRutingService) { "AutomatiskKontorRutingService must be configured for KafkaStreamPlugin" }

        val lockProvider = ExposedLockProvider(database)

        val endringPaOppfolgingsBrukerConsumer = EndringPaOppfolgingsBrukerConsumer()
        val oppfolgingsBrukerTopic = environment.config.property("topics.inn.endringPaOppfolgingsbruker").getString()

        val oppfolgingsPeriodeConsumer = OppfolgingsPeriodeConsumer(automatiskKontorRutingService)
        val oppfolgingsPeriodeTopic = environment.config.property("topics.inn.oppfolgingsperiodeV1").getString()

        val leesahConsumer = LeesahConsumer(automatiskKontorRutingService, fnrProvider)
        val leesahTopic = environment.config.property("topics.inn.pdlLeesah").getString()
        val spesificAvroValueSerde = LeesahAvroDeserializer(environment.config).valueDeserializer
        val specificAvroKeySerde = LeesahAvroDeserializer(environment.config).keyAvroSerde

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
        val kafkaStream = KafkaStreams(topology, configureKafkaStreams(environment.config))
        if (this.pluginConfig.meterRegistry != null) {
            val kafkaStreamsMetrics = KafkaStreamsMetrics(kafkaStream)
            kafkaStreamsMetrics.bindTo(this.pluginConfig.meterRegistry!!)
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
