package no.nav.kafka.retry.library.internal

import no.nav.db.table.FailedMessagesEntity
import no.nav.db.table.FailedMessagesTable
import no.nav.db.table.FailedMessagesTable.messageKeyText
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.OffsetDateTime

class FailedMessageRepository(val repositoryTopic: String) {

    fun hasFailedMessages(key: String): Boolean = transaction {
        if (key.isEmpty()) throw IllegalArgumentException("Key cannot be empty")
        if (key.isBlank()) throw IllegalArgumentException("Key cannot be blank")
        if (key.contains('\u0000')) throw IllegalArgumentException("Key: <${key}> contained contain null characters topic $repositoryTopic ")
        val messageOnTopicWithKeySubquery = FailedMessagesTable
            .select(FailedMessagesTable.id)
            .where { FailedMessagesTable.topic eq repositoryTopic and (messageKeyText eq key) }
        val existsOp = exists(messageOnTopicWithKeySubquery)
        FailedMessagesTable
            .select(existsOp)
            .map { it[existsOp] }
            .firstOrNull() ?: false
    }

    fun enqueue(keyString: String, keyBytes: ByteArray, value: ByteArray, reason: String): Unit = transaction {
        FailedMessagesTable.insert {
            it[messageKeyText] = keyString
            it[messageKeyBytes] = keyBytes
            it[messageValue] = value
            it[failureReason] = reason
            it[topic] = repositoryTopic
        }
    }

    // Henter en batch med meldinger klare for reprosessering
    fun getBatchToRetry(limit: Int): List<FailedMessage> = transaction {
        FailedMessagesEntity
            .find { FailedMessagesTable.topic eq repositoryTopic }
            .orderBy(FailedMessagesTable.id to SortOrder.ASC)
            .limit(limit)
            .map { it.toFailedMessage() }
    }

    fun delete(messageId: Long): Unit = transaction {
        FailedMessagesTable.deleteWhere { id eq messageId }
    }

    fun updateAfterFailedAttempt(messageId: Long, newReason: String): Unit = transaction {
        FailedMessagesTable
            .update({ FailedMessagesTable.id eq messageId })
            {
                with(SqlExpressionBuilder) {
                    it[retryCount] = retryCount + 1
                    it[lastAttemptTimestamp] = OffsetDateTime.now()
                    it[failureReason] = newReason
                }
            }
    }

    fun countTotalFailedMessages(): Long = transaction {
        FailedMessagesTable.selectAll().count()
    }
}

fun FailedMessagesEntity.toFailedMessage(): FailedMessage {
    return FailedMessage(
        id = this.id.value,
        messageKeyText = this.messageKeyText,
        messageKeyBytes = this.messageKeyBytes,
        messageValue = this.messageValue,
        queueTimestamp = this.queueTimestamp,
        lastAttemptTimestamp = this.lastAttemptTimestamp,
        retryCount = this.retryCount,
        failureReason = this.failureReason,
    )
}