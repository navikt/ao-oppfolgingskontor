package no.nav.kafka.retry.library.internal

import org.apache.kafka.streams.processor.StateStore
import org.apache.kafka.streams.processor.StateStoreContext

interface PostgresRetryStore : StateStore {
    fun hasFailedMessages(key: String): Boolean
    fun enqueue(key: String, value: ByteArray, reason: String)
    fun getBatchToRetry(limit: Int): List<FailedMessage>
    fun delete(messageId: Long)
    fun updateAfterFailedAttempt(messageId: Long, newReason: String)
}

internal class PostgresRetryStoreImpl(
    private val storeName: String,
    private val repository: FailedMessageRepository
) : PostgresRetryStore {
    private var open = false
    override fun name() = storeName
    override fun persistent() = true
    override fun isOpen() = open

    override fun init(context: StateStoreContext, root: StateStore) {
        open = true
    }

    override fun flush() {
        // Ingen spesifikk handling nødvendig for Postgres, da det er en ekstern store
    }
    override fun close() {
        // Ingen spesifikk handling nødvendig for Postgres, da det er en ekstern store
    }

    // Deleger kall til repository
    override fun hasFailedMessages(key: String) = repository.hasFailedMessages(key)
    override fun enqueue(key: String, value: ByteArray, reason: String) = repository.enqueue(key, value, reason)
    override fun getBatchToRetry(limit: Int): List<FailedMessage> = repository.getBatchToRetry(limit)
    override fun delete(messageId: Long) = repository.delete(messageId)
    override fun updateAfterFailedAttempt(messageId: Long, newReason: String) =
        repository.updateAfterFailedAttempt(messageId, newReason)
}