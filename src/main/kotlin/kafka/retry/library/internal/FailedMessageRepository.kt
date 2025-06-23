package no.nav.kafka.retry.library.internal

import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.KotlinPlugin
import javax.sql.DataSource

class FailedMessageRepository(dataSource: DataSource, val topic: String) {
    private val jdbi = Jdbi.create(dataSource).apply {
        installPlugin(KotlinPlugin())
    }

    fun hasFailedMessages(key: String): Boolean = jdbi.withHandle<Boolean, Exception> { handle ->
        handle.createQuery("SELECT EXISTS (SELECT 1 FROM failed_messages WHERE message_key_text = :key and topic = :topic)")
            .bind("key", key)
            .bind("topic", topic)
            .mapTo(Boolean::class.java)
            .one()
    }

    fun enqueue(keyString: String, keyBytes: ByteArray, value: ByteArray, reason: String) = jdbi.useHandle<Exception> { handle ->
        handle.createUpdate("""
            INSERT INTO failed_messages (message_key_text, message_key_bytes, message_value, failure_reason, topic)
            VALUES (:keyString, :keyBytes, :value, :reason)
        """)
            .bind("keyString", keyString)
            .bind("keyBytes", keyBytes)
            .bind("value", value)
            .bind("reason", reason)
            .bind("topic", topic)
            .execute()
    }

    // Henter en batch med meldinger klare for reprosessering
    fun getBatchToRetry(limit: Int): List<FailedMessage> = jdbi.withHandle<List<FailedMessage>, Exception> { handle ->
        handle.createQuery("""
            SELECT id, message_key_text, message_key_bytes, message_value, queue_timestamp, retry_count, last_attempt_timestamp, failure_reason
            FROM failed_messages
            WHERE topic = :topic
            ORDER BY queue_timestamp
            LIMIT :limit
        """)
            .bind("limit", limit)
            .bind("topic", topic)
            .mapTo(FailedMessage::class.java)
            .list()
    }

    fun delete(messageId: Long) = jdbi.useHandle<Exception> { handle ->
        handle.createUpdate("DELETE FROM failed_messages WHERE id = :id")
            .bind("id", messageId)
            .execute()
    }

    fun updateAfterFailedAttempt(messageId: Long, newReason: String) = jdbi.useHandle<Exception> { handle ->
        handle.createUpdate("""
            UPDATE failed_messages
            SET retry_count = retry_count + 1,
                last_attempt_timestamp = NOW(),
                failure_reason = :reason
            WHERE id = :id
        """)
            .bind("reason", newReason)
            .bind("id", messageId)
            .execute()
    }

    fun countTotalFailedMessages(): Long = jdbi.withHandle<Long, Exception> { handle ->
        handle.createQuery("SELECT COUNT(*) FROM failed_messages")
            .mapTo(Long::class.java)
            .one()
    }
}