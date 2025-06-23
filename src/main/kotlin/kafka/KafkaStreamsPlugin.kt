package no.nav.kafka

import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde
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
import no.nav.kafka.processor.LeesahDto
import no.nav.kafka.processor.ProcessRecord
import no.nav.kafka.processor.RecordProcessingResult
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

        val leesahConsumer: ProcessRecord<String, LeesahDto> = { a, b -> RecordProcessingResult.COMMIT }
        val leesahTopic = environment.config.property("pdl.leesah-v1").getString()
        val spesificAvroSerde = SpecificAvroSerde<LeesahDto>().apply {
            this.
            configure(
                mapOf(
                    "schema.registry.url" to environment.config.property("kafka.schema-registry").getString(),
                    "specific.avro.reader" to true
                ),
                false
            )
        }


        val topology = configureTopology<LeesahDto>(listOf(
            oppfolgingsBrukerTopic to { record, maybeRecordMetadata ->
                endringPaOppfolgingsBrukerConsumer.consume(record, maybeRecordMetadata) },
            oppfolgingsPeriodeTopic to { record, maybeRecordMetadata ->
                oppfolgingsPeriodeConsumer.consume(record, maybeRecordMetadata) }),
            listOf(Triple(leesahTopic, leesahConsumer, spesificAvroSerde))
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
