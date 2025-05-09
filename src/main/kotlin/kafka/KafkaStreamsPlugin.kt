package no.nav.kafka

import io.ktor.events.EventDefinition
import io.ktor.server.application.*
import io.ktor.server.application.hooks.MonitoringEvent
import no.nav.kafka.config.StreamsLifecycleManager
import no.nav.kafka.config.configureStream
import no.nav.kafka.config.configureTopology
import org.apache.kafka.streams.KafkaStreams
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource

val KafkaStreamsStarting: EventDefinition<Application> = EventDefinition()
val KafkaStreamsStarted: EventDefinition<Application> = EventDefinition()
val KafkaStreamsStopping: EventDefinition<Application> = EventDefinition()
val KafkaStreamsStopped: EventDefinition<Application> = EventDefinition()

class KafkaStreamsPluginConfig(
    var dataSource: DataSource? = null
)

data class KafkaStreamsInstance (
    val name: String,
    val streams: KafkaStreams,
    val isRunningFlag: AtomicBoolean
)

val KafkaStreamsPlugin: ApplicationPlugin<Unit> =
    createApplicationPlugin("KafkaStreams") {
        val streamsLifecycleManager = StreamsLifecycleManager()
        val consumer = EndringPaOppfolgingsBrukerConsumer()
        val oppfolgingsBrukerTopic = environment.config.property("topics.inn.endringPaOppfolgingsbruker").getString()
        val topology = configureTopology(oppfolgingsBrukerTopic, { record, maybeRecordMetadata ->
            consumer.consume(record, maybeRecordMetadata) })
        val kafkaStream = configureStream(topology, environment.config)
        val kafkaStreamsInstance = KafkaStreamsInstance("ArenaEndringPaOppfolgingsBruker", kafkaStream, AtomicBoolean(false))
        val kafkaStreams = listOf(kafkaStreamsInstance)

        kafkaStreams.forEach{
            streamsLifecycleManager.setupStreamsInstanceLifecycleHandler(it)
        }

        on(MonitoringEvent(ApplicationStarted)) { application ->
            application.log.info("Starter Kafka Streams")
            application.monitor.raise(KafkaStreamsStarting, application)
            kafkaStreams.forEach {
                streamsLifecycleManager.startStreamsApp(it)
            }
            application.monitor.raise(KafkaStreamsStarted, application)
        }

        on(MonitoringEvent(ApplicationStopping)) { application ->
            application.log.info("Stopper Kafka Streams")
            application.monitor.raise(KafkaStreamsStopping, application)
            kafkaStreams.forEach {
                streamsLifecycleManager.closeStreamsInstance(it)
            }
            application.monitor.raise(KafkaStreamsStopped, application)
        }
    }
