package no.nav.kafka

import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde
import io.ktor.events.EventDefinition
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationPlugin
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.MonitoringEvent
import io.ktor.server.application.log
import no.nav.kafka.config.AvroTopicConsumer
import no.nav.kafka.config.StringTopicConsumer
import no.nav.kafka.config.configureKafkaStreams
import no.nav.kafka.config.configureTopology
import no.nav.kafka.consumers.EndringPaOppfolgingsBrukerConsumer
import no.nav.kafka.consumers.LeesahConsumer
import no.nav.kafka.consumers.OppfolgingsPeriodeConsumer
import no.nav.kafka.processor.LeesahAvroDeserializer
import no.nav.services.AutomatiskKontorRutingService
import org.apache.kafka.streams.KafkaStreams
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

        val oppfolgingsPeriodeConsumer = OppfolgingsPeriodeConsumer(automatiskKontorRutingService)
        val oppfolgingsPeriodeTopic = environment.config.property("topics.inn.oppfolgingsperiodeV1").getString()

        val leesahConsumer = LeesahConsumer()
        val leesahTopic = environment.config.property("topics.inn.pdlLeesah").getString()
        val spesificAvroSerde = LeesahAvroDeserializer(environment.config).deserializer

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
                leesahTopic, leesahConsumer::consume, spesificAvroSerde
            ))
        )

        val kafkaStream = KafkaStreams(topology, configureKafkaStreams(environment.config))

        val shutDownTimeout = Duration.ofSeconds(1)

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
