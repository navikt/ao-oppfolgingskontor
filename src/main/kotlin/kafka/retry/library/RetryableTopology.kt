package no.nav.kafka.retry.library
import no.nav.kafka.retry.library.internal.FailedMessageRepository
import no.nav.kafka.retry.library.internal.PostgresRetryStoreBuilder
import no.nav.kafka.retry.library.internal.RetryableProcessor
import org.apache.kafka.common.serialization.Serde
import org.apache.kafka.streams.StreamsBuilder
import org.apache.kafka.streams.kstream.Consumed
import org.apache.kafka.streams.kstream.KStream
import org.apache.kafka.streams.processor.api.ProcessorSupplier
import org.apache.kafka.streams.processor.api.Record
import javax.sql.DataSource

/**
 * Legger til en gjenbrukbar, feiltolerant prosessor i en Kafka Streams-topologi.
 * Meldinger som feiler, eller som kommer bak en feilet melding for samme nøkkel,
 * blir lagret i PostgreSQL for periodisk reprosessering.
 *
 * RetryableTopology kan brykes på denne måten:
 * ```kotlin
// Terminal node eksempel - hvis du ikke ønsker å sende videre
val builder = StreamsBuilder()
RetryableTopology.addTerminalRetryableProcessor<String, String>(
builder = builder,
inputTopic = "input-topic",
// ...
businessLogic = { record -> /* gjør noe, returner Unit */ }
)
// Bygg topologien: val topology = builder.build()

// Transformerende eksempel - hvis du ønsker å sende videre
val builder = StreamsBuilder()
val outputStream = RetryableTopology.addTransformingRetryableProcessor<String, String, String, Long>(
builder = builder,
inputTopic = "input-topic",
// ...
businessLogic = { record -> Record(record.key(), record.value().length.toLong(), record.timestamp()) }
)

outputStream.to("output-topic")
// Bygg topologien: val topology = builder.build()
```
 */
object RetryableTopology {


    /**
     * Versjon for "terminal node"-bruk.
     * Prosesserer meldinger av enhver type <K, V> og håndterer feil,
     * men sender ingenting videre i topologien.
     */
    inline fun <reified K, reified V> addTerminalRetryableProcessor(
        builder: StreamsBuilder,
        inputTopic: String,
        dataSource: DataSource,
        keySerde: Serde<K>,
        valueSerde: Serde<V>,
        config: RetryConfig = RetryConfig(),
        noinline businessLogic: (record: Record<K, V>) -> Unit
    ) {
        // Liten lambda wrapper for å håndtere transformasjonen
        val transformingLogic: (Record<K, V>) -> Record<Void, Void>? = { record ->
            businessLogic(record) // Kall den originale logikken
            null // Returner null for å signalisere at ingenting skal sendes videre
        }

        addTransformingRetryableProcessor<K, V, Void, Void>(
            builder, inputTopic, dataSource,
            keyInSerde = keySerde,
            valueInSerde = valueSerde,
            config = config,
            businessLogic = transformingLogic
        )
    }

    /**
     * Legger til en transformerende retry-prosessor i topologien.
     * Den konsumerer fra 'inputTopic', prosesserer, og returnerer en ny KStream
     * med de vellykkede resultatene.
     */
    inline fun <reified KIn, reified VIn, reified KOut, reified VOut> addTransformingRetryableProcessor(
        builder: StreamsBuilder,
        inputTopic: String,
        dataSource: DataSource,
        keyInSerde: Serde<KIn>, // Kun input-SerDes er nødvendig
        valueInSerde: Serde<VIn>,
        config: RetryConfig = RetryConfig(),
        noinline businessLogic: (record: Record<KIn, VIn>) -> Record<KOut, VOut>?
    ): KStream<KOut, VOut> {

        val repository = FailedMessageRepository(dataSource)
        val storeBuilder = PostgresRetryStoreBuilder(config.stateStoreName, repository)
        builder.addStateStore(storeBuilder)

        val processorSupplier = ProcessorSupplier<KIn, VIn, KOut, VOut> {
            RetryableProcessor(
                config = config,
                keyInSerializer = keyInSerde.serializer(),
                valueInSerializer = valueInSerde.serializer(),
                keyInDeserializer = keyInSerde.deserializer(),
                valueInDeserializer = valueInSerde.deserializer(),
                topic = inputTopic,
                repository = repository,
                businessLogic = businessLogic
            )
        }

        val inputStream = builder.stream(inputTopic, Consumed.with(keyInSerde, valueInSerde))
        val processedStream =  inputStream.process(processorSupplier, config.stateStoreName)
        // Vi må filtrere ut null-verdiene som terminal-versjonen introduserer.
        @Suppress("UNCHECKED_CAST")
        return processedStream.filterNot { _, value -> value == null } as KStream<KOut, VOut>
    }
}