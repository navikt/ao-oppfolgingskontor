package no.nav.kafka.config

import Topic
import io.ktor.server.application.ApplicationEnvironment
import io.ktor.server.config.*
import kafka.consumers.IdentChangeProcessor
import kafka.consumers.OppfolgingsHendelseProcessor
import kafka.consumers.SisteOppfolgingsperiodeProcessor
import kafka.retry.library.RetryProcessorWrapper
import kafka.retry.library.StreamType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import net.javacrumbs.shedlock.core.LockProvider
import no.nav.kafka.consumers.EndringPaOppfolgingsBrukerProcessor
import no.nav.kafka.consumers.KontortilordningsProcessor
import no.nav.kafka.consumers.LeesahProcessor
import no.nav.kafka.consumers.SkjermingProcessor
import no.nav.kafka.processor.RecordProcessingResult
import no.nav.kafka.retry.library.RetryConfig
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.config.SslConfigs
import org.apache.kafka.common.serialization.Serde
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.StreamsBuilder
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.Topology
import org.apache.kafka.streams.errors.LogAndFailProcessingExceptionHandler
import org.apache.kafka.streams.kstream.Named
import org.apache.kafka.streams.processor.api.ProcessorSupplier
import org.apache.kafka.streams.processor.api.Record
import topics
import java.util.*

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
    sisteOppfolgingsperiodeProcessor: SisteOppfolgingsperiodeProcessor,
    kontortilordningsProcessor: KontortilordningsProcessor,
    leesahProcessor: LeesahProcessor,
    skjermingProcessor: SkjermingProcessor,
    endringPaOppfolgingsBrukerProcessor: EndringPaOppfolgingsBrukerProcessor,
    identEndringsProcessor: IdentChangeProcessor,
    oppfolgingsHendelseProcessor: OppfolgingsHendelseProcessor
): Topology {
    val topics = environment.topics()
    val builder = StreamsBuilder()

    fun <KIn, VIn, KOut, VOut> wrapInRetryProcessor(
        keyInSerde: Serde<KIn>,
        valueInSerde: Serde<VIn>,
        topic: String,
        streamType: StreamType,
        businessLogic: (Record<KIn, VIn>) -> RecordProcessingResult<KOut, VOut>,
    ): ProcessorSupplier<KIn, VIn, KOut, VOut> {
        return RetryProcessorWrapper.wrapInRetryProcessor(
            config = RetryConfig(),
            keyInSerde = keyInSerde,
            valueInSerde = valueInSerde,
            topic = topic,
            streamType = streamType,
            businessLogic = businessLogic,
            lockProvider = lockProvider,
            punctuationCoroutineScope = punctuationCoroutineScope,
        )
    }

    fun <KIn, VIn, KOut, VOut> wrapInRetryProcessor(topic: Topic<KIn, VIn>, streamType: StreamType, businessLogic: (Record<KIn, VIn>) -> RecordProcessingResult<KOut, VOut>)
        = wrapInRetryProcessor(topic.keySerde, topic.valSerde, topic.name, streamType, businessLogic)

    val kontortilordningProcessorSupplier = wrapInRetryProcessor(
        keyInSerde = KontortilordningsProcessor.identSerde,
        valueInSerde = KontortilordningsProcessor.oppfolgingsperiodeStartetSerde,
        topic = KontortilordningsProcessor.processorName,
        streamType = StreamType.INTERNAL,
        businessLogic = kontortilordningsProcessor::process,
    )

    val oppfolgingHendelseProcessorSupplier = wrapInRetryProcessor(
        topic = topics.inn.oppfolgingsHendelser,
        streamType = StreamType.SOURCE,
        businessLogic = oppfolgingsHendelseProcessor::process
    )
    builder.stream(topics.inn.oppfolgingsHendelser.name, topics.inn.oppfolgingsHendelser.consumedWith())
        .process(oppfolgingHendelseProcessorSupplier, Named.`as`(processorName(topics.inn.oppfolgingsHendelser.name)))
        .process(kontortilordningProcessorSupplier, Named.`as`(KontortilordningsProcessor.processorName))

    /*
    * Siste oppfolgingsperiode
    * */
    val oppfolgingsperiodeProcessorSupplier = wrapInRetryProcessor(
        topic = topics.inn.sisteOppfolgingsperiodeV1,
        streamType = StreamType.SOURCE,
        businessLogic = sisteOppfolgingsperiodeProcessor::process,
    )
    builder.stream(topics.inn.sisteOppfolgingsperiodeV1.name, topics.inn.sisteOppfolgingsperiodeV1.consumedWith())
        .process(oppfolgingsperiodeProcessorSupplier, Named.`as`(processorName(topics.inn.sisteOppfolgingsperiodeV1.name)))
        .process(kontortilordningProcessorSupplier, Named.`as`(KontortilordningsProcessor.processorName + "-fra-siste-oppfolgingsperiodeV1"))

    /*
    * Endring på oppfølgingsbruker (Arena)
    * */
    val endringPaOppfolgingsBrukerProcessorSupplier = wrapInRetryProcessor(
        topic = topics.inn.endringPaOppfolgingsbruker,
        streamType = StreamType.SOURCE,
        businessLogic = endringPaOppfolgingsBrukerProcessor::process
    )
    builder.stream(topics.inn.endringPaOppfolgingsbruker.name, topics.inn.endringPaOppfolgingsbruker.consumedWith())
        .process(endringPaOppfolgingsBrukerProcessorSupplier, Named.`as`(processorName(topics.inn.endringPaOppfolgingsbruker.name)))

    /*
    * Skjerming
    * */
    val skjermingProcessorSupplier = wrapInRetryProcessor(
        topic = topics.inn.skjerming,
        streamType = StreamType.SOURCE,
        businessLogic = skjermingProcessor::process
    )
    builder.stream(topics.inn.skjerming.name, topics.inn.skjerming.consumedWith())
        .process(skjermingProcessorSupplier, Named.`as`(processorName(topics.inn.skjerming.name)))

    /*
    * LEESAH hendelser fra PDL
    * */
    val leesahProcessorSupplier = wrapInRetryProcessor(
        topic = topics.inn.pdlLeesah,
        streamType = StreamType.SOURCE,
        businessLogic = leesahProcessor::process
    )
    builder.stream(topics.inn.pdlLeesah.name, topics.inn.pdlLeesah.consumedWith())
        .process(leesahProcessorSupplier, Named.`as`(processorName(topics.inn.pdlLeesah.name)))

    /*
     * AKTOR V2
     * */
    val identChangeProcessorSupplier = wrapInRetryProcessor(
        topic = topics.inn.aktorV2,
        streamType = StreamType.SOURCE,
        businessLogic = identEndringsProcessor::process
    )
    builder.stream(topics.inn.aktorV2.name, topics.inn.aktorV2.consumedWith())
        .process(identChangeProcessorSupplier, Named.`as`(processorName(topics.inn.aktorV2.name)))

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
    put(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG, appConfig.property("kafka.instance-id").getString())
    put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "250")
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
