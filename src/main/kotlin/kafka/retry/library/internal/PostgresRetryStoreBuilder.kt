package no.nav.kafka.retry.library.internal


import org.apache.kafka.streams.state.StoreBuilder
import org.slf4j.LoggerFactory


internal class PostgresRetryStoreBuilder(
    private val storeName: String,
    private val repository: FailedMessageRepository
) : StoreBuilder<PostgresRetryStore> {
    val logger = LoggerFactory.getLogger(PostgresRetryStoreBuilder::class.java)
    /**
     * Navnet på state store som brukes i Kafka Streams.
     * Dette må være unikt i applikasjonen.
     */
    override fun name(): String = storeName

    /**
     * Dette er den viktigste metoden. Den kalles av Kafka Streams for å
     * lage en instans av vår custom state store.
     */
    override fun build(): PostgresRetryStore {
        return PostgresRetryStoreImpl(storeName, repository)
    }

    // --- Metoder vi ikke støtter, men som må implementeres ---
    // Vi returnerer `this` for å ikke brekke API-kall, men logger en advarsel.

    override fun withCachingEnabled(): StoreBuilder<PostgresRetryStore> {
        // Caching håndteres ikke av Kafka Streams for en ekstern store.
        logger.warn("Caching is not supported for PostgresRetryStore and the call will be ignored.")
        return this
    }

    override fun withCachingDisabled(): StoreBuilder<PostgresRetryStore> {
        return this // Ingen handling nødvendig
    }

    override fun withLoggingEnabled(config: Map<String, String>): StoreBuilder<PostgresRetryStore> {
        // Endringslogg til et Kafka-topic er for RocksDB-stores, ikke relevant for oss.
        logger.warn("Changelogging is not supported for PostgresRetryStore and the call will be ignored.")
        return this
    }

    override fun withLoggingDisabled(): StoreBuilder<PostgresRetryStore> {
        return this // Ingen handling nødvendig
    }

    override fun logConfig(): Map<String, String> = emptyMap()

    override fun loggingEnabled(): Boolean = false
}