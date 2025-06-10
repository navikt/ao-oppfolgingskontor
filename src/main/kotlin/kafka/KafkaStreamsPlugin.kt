package no.nav.kafka

import io.ktor.events.EventDefinition
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationPlugin
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.MonitoringEvent
import io.ktor.server.application.log
import no.nav.http.client.PoaoTilgangKtorHttpClient
import no.nav.kafka.config.configureStream
import no.nav.kafka.config.configureTopology
import no.nav.kafka.consumers.EndringPaOppfolgingsBrukerConsumer
import no.nav.kafka.consumers.OppfolgingsPeriodeConsumer
import no.nav.services.AutomatiskKontorRutingService
import no.nav.services.KontorTilhorighetService
import no.nav.services.KontorTilordningService
import java.time.Duration

val KafkaStreamsStarting: EventDefinition<Application> = EventDefinition()
val KafkaStreamsStarted: EventDefinition<Application> = EventDefinition()
val KafkaStreamsStopping: EventDefinition<Application> = EventDefinition()
val KafkaStreamsStopped: EventDefinition<Application> = EventDefinition()

class KafkaStreamsPluginConfig(
    var automatiskKontorRutingService: AutomatiskKontorRutingService? = null,
)

val KafkaStreamsPlugin: ApplicationPlugin<KafkaStreamsPluginConfig> =
    createApplicationPlugin("KafkaStreams", ::KafkaStreamsPluginConfig) {
        val automatiskKontorRutingService = requireNotNull(this.pluginConfig.automatiskKontorRutingService)

        val endringPaOppfolgingsBrukerConsumer = EndringPaOppfolgingsBrukerConsumer()
        val oppfolgingsBrukerTopic = environment.config.property("topics.inn.endringPaOppfolgingsbruker").getString()
        val oppdaterArenaKontorTopology = configureTopology(oppfolgingsBrukerTopic, { record, maybeRecordMetadata ->
            endringPaOppfolgingsBrukerConsumer.consume(record, maybeRecordMetadata) })

        val oppfolgingsPeriodeConsumer = OppfolgingsPeriodeConsumer(automatiskKontorRutingService)
        val oppfolgingsPeriodeTopic = environment.config.property("topics.inn.endringPaOppfolgingsbruker").getString()
        val kontorRutingTopology = configureTopology(oppfolgingsPeriodeTopic, { record, maybeRecordMetadata ->
            oppfolgingsPeriodeConsumer.consume(record, maybeRecordMetadata) })

        val kafkaStreams = configureStream(listOf(
            oppdaterArenaKontorTopology,
            kontorRutingTopology
        ), environment.config)

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