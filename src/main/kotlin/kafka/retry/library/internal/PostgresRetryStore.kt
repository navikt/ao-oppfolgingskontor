package no.nav.kafka.retry.library.internal

import kafka.retry.library.internal.KafkaOffsetRepository
import org.apache.kafka.streams.processor.StateStore
import org.apache.kafka.streams.processor.StateStoreContext

interface PostgresRetryStore : StateStore {
    fun hasFailedMessages(key: String): Boolean
    fun enqueue(keyString: String, keyBytes: ByteArray, value: ByteArray, reason: String): Long
    fun enqueue(keyString: String, keyBytes: ByteArray, value: ByteArray, reason: String, humanReadableValue: String?): Long
    fun getBatchToRetry(limit: Int): List<FailedMessage>
    fun delete(messageId: Long)
    fun updateAfterFailedAttempt(messageId: Long, newReason: String)
    fun saveOffset(topic: String, partition: Int, offset: Long)
}

internal class PostgresRetryStoreImpl(
    private val storeName: String,
    private val failedMessageRepository: FailedMessageRepository,
    private val kafkaOffsetRepository: KafkaOffsetRepository,
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
    override fun hasFailedMessages(key: String) = failedMessageRepository.hasFailedMessages(key)
    override fun enqueue(keyString: String, keyBytes: ByteArray, value: ByteArray, reason: String) = failedMessageRepository.enqueue(keyString, keyBytes, value, reason)
    override fun enqueue(keyString: String, keyBytes: ByteArray, value: ByteArray, reason: String, humanReadableValue: String?) = failedMessageRepository.enqueue(keyString, keyBytes, value, reason, humanReadableValue)
    override fun getBatchToRetry(limit: Int): List<FailedMessage> = failedMessageRepository.getBatchToRetry(limit)
    override fun delete(messageId: Long) = failedMessageRepository.delete(messageId)
    override fun updateAfterFailedAttempt(messageId: Long, newReason: String) =
        failedMessageRepository.updateAfterFailedAttempt(messageId, newReason)
    override fun saveOffset(topic: String, partition: Int, offset: Long) {
        kafkaOffsetRepository.storeOffset(topic, partition, offset)
    }
}