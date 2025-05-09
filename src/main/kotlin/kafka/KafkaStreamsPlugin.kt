package no.nav.kafka

import io.ktor.events.EventDefinition
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationPlugin
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.MonitoringEvent
import io.ktor.server.application.log
import no.nav.kafka.config.configureStream
import no.nav.kafka.config.configureTopology
import no.nav.kafka.exceptionHandler.KafkaStreamsTaskMonitor
import java.time.Duration
import javax.sql.DataSource

val KafkaStreamsStarting: EventDefinition<Application> = EventDefinition()
val KafkaStreamsStarted: EventDefinition<Application> = EventDefinition()
val KafkaStreamsStopping: EventDefinition<Application> = EventDefinition()
val KafkaStreamsStopped: EventDefinition<Application> = EventDefinition()

class KafkaStreamsPluginConfig(
    var dataSource: DataSource? = null,
    var monitor: KafkaStreamsTaskMonitor? = null,
)

val KafkaStreamsPlugin: ApplicationPlugin<KafkaStreamsPluginConfig> =
    createApplicationPlugin("KafkaStreams", ::KafkaStreamsPluginConfig) {
        val monitor = pluginConfig.monitor ?: throw IllegalStateException("Monitor must be set in the plugin config")
        val consumer = EndringPaOppfolgingsBrukerConsumer()
        val oppfolgingsBrukerTopic = environment.config.property("topics.inn.endringPaOppfolgingsbruker").getString()
        val topology = configureTopology(oppfolgingsBrukerTopic, { record, maybeRecordMetadata ->
            consumer.consume(record, maybeRecordMetadata)
        }, monitor = monitor)
        val kafkaStreams = listOf(configureStream(topology, environment.config, monitor))

        val shutDownTimeout = Duration.ofSeconds(1)

        on(MonitoringEvent(ApplicationStarted)) { application ->
            application.log.info("Starter Kafka Streams")
            application.monitor.raise(KafkaStreamsStarting, application)
            kafkaStreams.forEach { stream -> stream.start() }
            application.monitor.raise(KafkaStreamsStarted, application)
        }

        on(MonitoringEvent(ApplicationStopping)) { application ->
            application.log.info("Stopper Kafka Streams")
            application.monitor.raise(KafkaStreamsStopping, application)
            kafkaStreams.forEach { stream -> stream.close(shutDownTimeout) }
            application.monitor.raise(KafkaStreamsStopped, application)
        }
    }
