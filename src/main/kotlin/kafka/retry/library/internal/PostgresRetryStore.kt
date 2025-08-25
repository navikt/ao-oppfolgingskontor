package no.nav.kafka.retry.library.internal

import org.apache.kafka.streams.processor.StateStore
import org.apache.kafka.streams.processor.StateStoreContext

interface PostgresRetryStore : StateStore {
    fun hasFailedMessages(key: RetryKey): Boolean
    fun enqueue(keyString: RetryKey, keyBytes: ByteArray, value: ByteArray?, reason: String): Long
    fun enqueue(keyString: RetryKey, keyBytes: ByteArray, value: ByteArray?, reason: String, humanReadableValue: String?): Long
    fun getBatchToRetry(limit: Int): List<FailedMessage>
    fun delete(messageId: Long)
    fun updateAfterFailedAttempt(messageId: Long, newReason: String)
    fun saveOffset(partition: Int, offset: Long)
}

internal class PostgresRetryStoreImpl(
    private val storeName: String,
    private val retryableRepository: RetryableRepository,
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
    override fun hasFailedMessages(key: RetryKey) = retryableRepository.hasFailedMessages(key)
    override fun enqueue(keyString: RetryKey, keyBytes: ByteArray, value: ByteArray?, reason: String) = retryableRepository.enqueue(keyString, keyBytes, value, reason)
    override fun enqueue(keyString: RetryKey, keyBytes: ByteArray, value: ByteArray?, reason: String, humanReadableValue: String?) = retryableRepository.enqueue(keyString, keyBytes, value, reason, humanReadableValue)
    override fun getBatchToRetry(limit: Int): List<FailedMessage> = retryableRepository.getBatchToRetry(limit)
    override fun delete(messageId: Long) = retryableRepository.delete(messageId)
    override fun updateAfterFailedAttempt(messageId: Long, newReason: String) =
        retryableRepository.updateAfterFailedAttempt(messageId, newReason)
    override fun saveOffset(partition: Int, offset: Long) {
        retryableRepository.saveOffsetIfGreater(partition, offset)
    }
}