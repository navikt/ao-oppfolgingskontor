package no.nav.kafka.retry.library
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import net.javacrumbs.shedlock.core.LockProvider
import no.nav.kafka.config.processorName
import no.nav.kafka.processor.RecordProcessingResult
import no.nav.kafka.retry.library.internal.FailedMessageRepository
import no.nav.kafka.retry.library.internal.RetryableProcessor
import org.apache.kafka.common.serialization.Serde
import org.apache.kafka.streams.StreamsBuilder
import org.apache.kafka.streams.kstream.Consumed
import org.apache.kafka.streams.kstream.KStream
import org.apache.kafka.streams.kstream.Named
import org.apache.kafka.streams.processor.api.ProcessorSupplier
import org.apache.kafka.streams.processor.api.Record

/**
 * Legger til en gjenbrukbar, feiltolerant prosessor i en Kafka Streams-topologi.
 * Meldinger som feiler, eller som kommer bak en feilet melding for samme nøkkel,
 * blir lagret i PostgreSQL for periodisk reprosessering.
 *
 * RetryableTopology kan brykes på denne måten:
 * ```kotlin
// Terminal node eksempel - hvis du ikke ønsker å sende videre
val builder = StreamsBuilder()
RetryableTopology.addRetryableProcessor<String, String>(
builder = builder,
inputTopic = "input-topic",
// ...
businessLogic = { record -> /* gjør noe, returner ProcessingResylt */ }
)
// Bygg topologien: val topology = builder.build()

// Transformerende eksempel - hvis du ønsker å sende videre
val builder = StreamsBuilder()
val outputStream = RetryableTopology.addRetryableProcessor<String, String, String, Long>(
builder = builder,
inputTopic = "input-topic",
// ...
businessLogic = { record -> Forward(Record(record.key(), record.value().length.toLong(), record.timestamp())) }
)

outputStream.to("output-topic")
// Bygg topologien: val topology = builder.build()
```
 */
object RetryableTopology {


    /**
     * Versjon for "terminal node"-bruk.
     * Prosesserer meldinger av enhver type <K, V> og utfører en handling definert i 'businessLogic og håndterer feil.
     */
    inline fun <reified K, reified V> aaddRetryableProcessor(
        builder: StreamsBuilder,
        inputTopic: String,
        keyInSerde: Serde<K>,
        valueInSerde: Serde<V>,
        config: RetryConfig,
        noinline businessLogic: (record: Record<K, V>) -> RecordProcessingResult<Unit, Unit>,
        lockProvider: LockProvider,
        punctuationCoroutineScope: CoroutineScope,
    ) {
        addRetryableProcessor<K, V, Unit, Unit>(
            builder, inputTopic,
            keyInSerde = keyInSerde,
            valueInSerde = valueInSerde,
            config = config,
            businessLogic = businessLogic,
            lockProvider = lockProvider,
            punctuationCoroutineScope = punctuationCoroutineScope,
        )
    }

    /**
     * Legger til en  retry-prosessor med en typet context (KStream<KOut,VOut>) i topologien.
     * For å sende meldinger videre i topologien må 'businenessLogic' returnere en RecordProcessingResult av type Forward.
     * Vær oppmerksom på at contexten ved retry av meldinger er contexten ved punctueringstidspunktet, og vil ikke ha korrekt offset/partition
     */
    inline fun <reified KIn, reified VIn, reified KOut, reified VOut> addRetryableProcessor(
        builder: StreamsBuilder,
        inputTopic: String,
        keyInSerde: Serde<KIn>, // Kun input-SerDes er nødvendig
        valueInSerde: Serde<VIn>,
        config: RetryConfig = RetryConfig(),
        noinline businessLogic: (record: Record<KIn, VIn>) -> RecordProcessingResult<KOut, VOut>,
        lockProvider: LockProvider,
        punctuationCoroutineScope: CoroutineScope,
    ): KStream<KOut, VOut> {

        val repository = FailedMessageRepository(inputTopic)

        val processorSupplier = ProcessorSupplier {
            RetryableProcessor(
                config = config,
                keyInSerializer = keyInSerde.serializer(),
                valueInSerializer = valueInSerde.serializer(),
                keyInDeserializer = keyInSerde.deserializer(),
                valueInDeserializer = valueInSerde.deserializer(),
                topic = inputTopic,
                repository = repository,
                businessLogic = businessLogic,
                lockProvider = lockProvider,
                punctuationCoroutineScope = punctuationCoroutineScope,
            )
        }

        return builder.stream(inputTopic, Consumed.with(keyInSerde, valueInSerde))
            .process(processorSupplier,  Named.`as`(processorName(inputTopic)),)
    }
}