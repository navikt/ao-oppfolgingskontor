package no.nav.kafka.config

import Topics
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde
import io.ktor.server.application.ApplicationEnvironment
import io.ktor.server.config.*
import kafka.consumers.OppfolgingsPeriodeStartetSerde
import kafka.consumers.SisteOppfolgingsperiodeProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import net.javacrumbs.shedlock.core.LockProvider
import no.nav.db.Ident
import no.nav.domain.externalEvents.OppfolgingsperiodeStartet
import no.nav.isProduction
import no.nav.kafka.consumers.EndringPaOppfolgingsBrukerProcessor
import no.nav.kafka.consumers.KontortilordningsProcessor
import no.nav.kafka.consumers.LeesahProcessor
import no.nav.kafka.consumers.SkjermingProcessor
import no.nav.kafka.processor.LeesahAvroSerdes
import no.nav.kafka.processor.ProcessRecord
import no.nav.kafka.processor.RecordProcessingResult
import no.nav.kafka.retry.library.RetryConfig
import no.nav.kafka.retry.library.internal.FailedMessageRepository
import no.nav.kafka.retry.library.internal.RetryableProcessor
import no.nav.person.pdl.leesah.Personhendelse
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.config.SslConfigs
import org.apache.kafka.common.serialization.Serde
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.StreamsBuilder
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.Topology
import org.apache.kafka.streams.errors.LogAndFailProcessingExceptionHandler
import org.apache.kafka.streams.kstream.Consumed
import org.apache.kafka.streams.kstream.Named
import org.apache.kafka.streams.processor.api.ProcessorSupplier
import org.apache.kafka.streams.processor.api.Record
import java.util.*

sealed class Consumer()

sealed class TopicConsumer(val topic: String) : Consumer()
sealed class StreamsConsumer(val streamName: String, val parentName: String) : Consumer()

class StringTopicConsumer(
    topic: String,
    val processRecord: ProcessRecord<String, String, String, String>,
    val sink: StringStringSinkConfig? = null
) : TopicConsumer(topic)

class OppfolgingsperiodeStartetStreamConsumer(
    streamName: String,
    parentName: String,
    val processRecord: ProcessRecord<Ident, OppfolgingsperiodeStartet, String, String>,
) : StreamsConsumer(streamName, parentName)

class AvroTopicConsumer(
    topic: String,
    val processRecord: ProcessRecord<String, Personhendelse, String, String>,
    val valueSerde: SpecificAvroSerde<Personhendelse>,
    val keySerde: Serde<String>,
    val sink: StringStringSinkConfig?
) : TopicConsumer(topic)

open class SinkConfig<K, V>(
    val sinkName: String,
    val outputTopicName: String,
    val keySerde: Serde<K>,
    val valueSerde: Serde<V>,
) {
    override fun equals(other: Any?): Boolean {
        if (other !is SinkConfig<*, *>) return false
        if (sinkName != other.sinkName) return false
        if (outputTopicName != other.outputTopicName) return false
        if (keySerde != other.keySerde) return false
        if (valueSerde != other.valueSerde) return false
        return true
    }
}

class StringStringSinkConfig(
    sinkName: String,
    outputTopicName: String,
) : SinkConfig<String, String>(
    sinkName,
    outputTopicName,
    Serdes.String(),
    Serdes.String(),
)

fun processorName(topic: String): String {
    return "${topic}-processor"
}

fun configureTopology(
    environment: ApplicationEnvironment,
    lockProvider: LockProvider,
    punctuationCoroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    topics: Topics,
    sisteOppfolgingsperiodeProcessor: SisteOppfolgingsperiodeProcessor,
    kontortilordningsProcessor: KontortilordningsProcessor,
    leesahProcessor: LeesahProcessor,
    skjermingProcessor: SkjermingProcessor,
    endringPaOppfolgingsBrukerProcessor: EndringPaOppfolgingsBrukerProcessor,
): Topology {

    val builder = StreamsBuilder()

    fun <KIn, VIn, KOut, VOut> wrapInRetryProcessor(
        keyInSerde: Serde<KIn>,
        valueInSerde: Serde<VIn>,
        topic: String,
        businessLogic: (Record<KIn, VIn>) -> RecordProcessingResult<KOut, VOut>,
    ): ProcessorSupplier<KIn, VIn, KOut, VOut> {

        val repository = FailedMessageRepository(topic)
        return ProcessorSupplier {
            RetryableProcessor(
                config = RetryConfig(),
                keyInSerde = keyInSerde,
                valueInSerde = valueInSerde,
                topic = topic,
                repository = repository,
                businessLogic = businessLogic,
                lockProvider = lockProvider,
                punctuationCoroutineScope = punctuationCoroutineScope,
            )
        }
    }

    val oppfolgingsperiodeProcessorSupplier = wrapInRetryProcessor(
            keyInSerde = Serdes.String(),
            valueInSerde = Serdes.String(),
            topic = topics.inn.sisteOppfolgingsperiodeV1,
            businessLogic = sisteOppfolgingsperiodeProcessor::process,
    )

    val kontortilordningProcessorSupplier = wrapInRetryProcessor(
            keyInSerde = KontortilordningsProcessor.identSerde,
            valueInSerde = KontortilordningsProcessor.oppfolgingsperiodeStartetSerde,
            topic = KontortilordningsProcessor.processorName,
            businessLogic = kontortilordningsProcessor::process,
    )

    builder.stream(topics.inn.sisteOppfolgingsperiodeV1, Consumed.with(Serdes.String(), Serdes.String())).process(
            oppfolgingsperiodeProcessorSupplier, Named.`as`(processorName(topics.inn.sisteOppfolgingsperiodeV1))
        ).process(
            kontortilordningProcessorSupplier, Named.`as`(KontortilordningsProcessor.processorName)
        )


    if(!environment.isProduction()) {
        val leesahProcessorSupplier = wrapInRetryProcessor(
            keyInSerde = LeesahAvroSerdes(environment.config).keyAvroSerde,
            valueInSerde = LeesahAvroSerdes(environment.config).valueAvroSerde,
            topic = topics.inn.pdlLeesah,
            businessLogic = leesahProcessor::process
        )

        builder.stream(
            topics.inn.pdlLeesah,
            Consumed.with(LeesahAvroSerdes(environment.config).keyAvroSerde, LeesahAvroSerdes(environment.config).valueAvroSerde)
        ).process(leesahProcessorSupplier, Named.`as`(processorName(topics.inn.pdlLeesah)))

        val skjermingProcessorSupplier = wrapInRetryProcessor(
            keyInSerde = Serdes.String(),
            valueInSerde = Serdes.String(),
            topic = topics.inn.skjerming,
            businessLogic = skjermingProcessor::process
        )

        builder.stream(
            topics.inn.skjerming,
            Consumed.with(Serdes.String(), Serdes.String())
        ).process(skjermingProcessorSupplier, Named.`as`(processorName(topics.inn.skjerming)))

        val endringPaOppfolgingsBrukerProcessorSupplier = wrapInRetryProcessor(
            keyInSerde = Serdes.String(),
            valueInSerde = Serdes.String(),
            topic = topics.inn.endringPaOppfolgingsbruker,
            businessLogic = endringPaOppfolgingsBrukerProcessor::process
        )

        builder.stream(
            topics.inn.endringPaOppfolgingsbruker,
            Consumed.with(Serdes.String(), Serdes.String())
        ).process(endringPaOppfolgingsBrukerProcessorSupplier, Named.`as`(processorName(topics.inn.endringPaOppfolgingsbruker)))
    }

    val topology = builder.build()

    return topology
}

fun kafkaStreamsProps(config: ApplicationConfig): Properties {
    val naisKafkaEnv = config.toKafkaEnv()
    return Properties().streamsConfig(naisKafkaEnv, config).streamsErrorHandlerConfig().securityConfig(naisKafkaEnv)
}

private fun Properties.streamsConfig(config: NaisKafkaEnv, appConfig: ApplicationConfig): Properties {
    put(StreamsConfig.APPLICATION_ID_CONFIG, appConfig.property("kafka.application-id").getString())
    put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, config.KAFKA_BROKERS)
    put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().javaClass.name)
    put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().javaClass.name)
    put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, "1000") // Control commit interval
    put(StreamsConfig.producerPrefix(ProducerConfig.RETRIES_CONFIG), Int.MAX_VALUE) // Enable retries
    put(StreamsConfig.producerPrefix(ProducerConfig.ACKS_CONFIG), "all") // Ensure strong consistency
    return this
}

fun Properties.streamsErrorHandlerConfig(): Properties {
    put(StreamsConfig.PROCESSING_EXCEPTION_HANDLER_CLASS_CONFIG, LogAndFailProcessingExceptionHandler::class.java.name)
    return this
}

private fun Properties.securityConfig(config: NaisKafkaEnv): Properties {
    put(SslConfigs.SSL_TRUSTSTORE_TYPE_CONFIG, "JKS")
    put(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG, config.KAFKA_TRUSTSTORE_PATH)
    put(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG, config.KAFKA_CREDSTORE_PASSWORD)
    put(SslConfigs.SSL_KEYSTORE_TYPE_CONFIG, "PKCS12")
    put(SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG, config.KAFKA_KEYSTORE_PATH)
    put(SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG, config.KAFKA_CREDSTORE_PASSWORD)
    put(SslConfigs.SSL_KEY_PASSWORD_CONFIG, config.KAFKA_CREDSTORE_PASSWORD)
    put(SslConfigs.SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_CONFIG, "")
    put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SSL")
    return this
}
