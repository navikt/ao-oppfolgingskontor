package no.nav.kafka

import io.ktor.events.EventDefinition
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationPlugin
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.MonitoringEvent
import io.ktor.server.application.log
import no.nav.kafka.config.configureKafkaStreams
import no.nav.kafka.config.configureTopology
import no.nav.kafka.consumers.EndringPaOppfolgingsBrukerConsumer
import no.nav.kafka.consumers.OppfolgingsPeriodeConsumer
import no.nav.services.AutomatiskKontorRutingService
import org.apache.kafka.streams.KafkaStreams
import java.time.Duration
import javax.sql.DataSource

val KafkaStreamsStarting: EventDefinition<Application> = EventDefinition()
val KafkaStreamsStarted: EventDefinition<Application> = EventDefinition()
val KafkaStreamsStopping: EventDefinition<Application> = EventDefinition()
val KafkaStreamsStopped: EventDefinition<Application> = EventDefinition()

val shutDownTimeout = Duration.ofSeconds(1)

class KafkaStreamsPluginConfig(
    var automatiskKontorRutingService: AutomatiskKontorRutingService? = null,
    var dataSource: DataSource? = null
)

val KafkaStreamsPlugin: ApplicationPlugin<KafkaStreamsPluginConfig> =
    createApplicationPlugin("KafkaStreams", ::KafkaStreamsPluginConfig) {
        val dataSource = requireNotNull(this.pluginConfig.dataSource) { "DataSource must be configured for KafkaStreamsPlugin" }
        val automatiskKontorRutingService = requireNotNull(this.pluginConfig.automatiskKontorRutingService) { "AutomatiskKontorRutingService must be configured for KafkaStreamPlugin" }

        val endringPaOppfolgingsBrukerConsumer = EndringPaOppfolgingsBrukerConsumer()
        val oppfolgingsBrukerTopic = environment.config.property("topics.inn.endringPaOppfolgingsbruker").getString()

        val oppfolgingsPeriodeConsumer = OppfolgingsPeriodeConsumer(automatiskKontorRutingService)
        val oppfolgingsPeriodeTopic = environment.config.property("topics.inn.oppfolgingsperiodeV1").getString()

        val topology = configureTopology(listOf(
            oppfolgingsBrukerTopic to { record, maybeRecordMetadata ->
                endringPaOppfolgingsBrukerConsumer.consume(record, maybeRecordMetadata) },
            oppfolgingsPeriodeTopic to { record, maybeRecordMetadata ->
                oppfolgingsPeriodeConsumer.consume(record, maybeRecordMetadata) })
        ,dataSource)

        val kafkaStream = KafkaStreams(topology, configureKafkaStreams(environment.config))

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