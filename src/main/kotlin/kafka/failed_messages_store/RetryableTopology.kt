package no.nav.kafka.failed_messages_store
import org.apache.kafka.common.serialization.Serde
import org.apache.kafka.streams.StreamsBuilder
import org.apache.kafka.streams.kstream.Consumed
import org.apache.kafka.streams.processor.api.ProcessorSupplier
import org.apache.kafka.streams.processor.api.Record
import javax.sql.DataSource

object RetryableTopology {

    /**
     * Legger til en gjenbrukbar, feiltolerant prosessor i en Kafka Streams-topologi.
     * Meldinger som feiler, eller som kommer bak en feilet melding for samme nøkkel,
     * blir lagret i PostgreSQL for periodisk reprosessering.
     */
    fun <K, V> addRetryableProcessor(
        builder: StreamsBuilder,
        inputTopic: String,
        dataSource: DataSource,
        keySerde: Serde<K>,
        valueSerde: Serde<V>,
        config: RetryConfig = RetryConfig(),
        businessLogic: (record: Record<K, V>) -> Unit
    ) {
        // 1. Initialiser repository
        val repository = FailedMessageRepository(dataSource)

        // 2. Lag en StoreBuilder for vår custom state store
        val storeBuilder = PostgresRetryStoreBuilder(config.stateStoreName, repository)

        // 3. Legg state store-en til topologien ved hjelp av StoreBuilder
        builder.addStateStore(storeBuilder)

        // 4. Lag ProcessorSupplier
        val processorSupplier = ProcessorSupplier<K, V, Void, Void> {
            RetryableProcessor(
                config = config,
                keySerializer = keySerde.serializer(),
                valueSerializer = valueSerde.serializer(),
                valueDeserializer = valueSerde.deserializer(),
                topic = inputTopic,
                businessLogic = businessLogic
            )
        }

        // 5. Koble alt sammen i topologien (uendret)
        builder.stream(inputTopic, Consumed.with(keySerde, valueSerde))
            .process(processorSupplier, config.stateStoreName)
    }
}