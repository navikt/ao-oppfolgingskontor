package no.nav.kafka.retry.library
import no.nav.kafka.retry.library.internal.FailedMessageRepository
import no.nav.kafka.retry.library.internal.PostgresRetryStoreBuilder
import no.nav.kafka.retry.library.internal.RetryableProcessor
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
     *
     * RetryabbleTopology kan brykes på denne måten:
     * ```kotlin
    // MainApplication.kt
    import org.apache.kafka.common.serialization.Serdes
    import org.apache.kafka.streams.KafkaStreams
    import org.apache.kafka.streams.StreamsBuilder
    import java.util.*
    import com.zaxxer.hikari.HikariDataSource // Eksempel på DataSource

    fun main() {
    // 1. Sett opp avhengigheter (Kafka-props, DataSource)
    val props: Properties = //... dine kafka-konfigurasjoner

    val dataSource = HikariDataSource().apply {
    jdbcUrl = "jdbc:postgresql://localhost:5432/mydatabase"
    username = "user"
    password = "password"
    }

    val builder = StreamsBuilder()

    // 2. Definer din forretningslogikk som en lambda
    val myProcessingLogic: (Record<String, String>) -> Unit = { record ->
    println("Trying to process key=${record.key()}, value=${record.value()}")

    // Simuler en feil for visse meldinger
    if (record.value().contains("FAIL")) {
    throw RuntimeException("Simulated processing error!")
    }

    println("Successfully processed key=${record.key()}")
    // Her ville du f.eks. sendt resultatet videre til et annet topic
    }

    // 3. Bruk biblioteket til å bygge topologien
    RetryableTopology.addRetryableProcessor(
    builder = builder,
    inputTopic = "my-input-topic",
    dataSource = dataSource,
    keySerde = Serdes.String(),
    valueSerde = Serdes.String(),
    // config kan utelates for å bruke default
    businessLogic = myProcessingLogic
    )

    // 4. Start applikasjonen
    val topology = builder.build()
    println(topology.describe())

    val streams = KafkaStreams(topology, props)
    streams.start()

    Runtime.getRuntime().addShutdownHook(Thread {
    streams.close()
    dataSource.close()
    })
    }
    ```
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
                repository = repository,
                businessLogic = businessLogic
            )
        }

        // 5. Koble alt sammen i topologien (uendret)
        builder.stream(inputTopic, Consumed.with(keySerde, valueSerde))
            .process(processorSupplier, config.stateStoreName)
    }
}