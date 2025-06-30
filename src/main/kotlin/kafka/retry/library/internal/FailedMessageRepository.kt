package no.nav.kafka.retry.library.internal

import no.nav.db.table.FailedMessagesEntity
import no.nav.db.table.FailedMessagesTable
import no.nav.db.table.FailedMessagesTable.messageKeyText
import org.jetbrains.exposed.sql.SizedIterable
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.exists
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime

class FailedMessageRepository(val repositoryTopic: String) {

    fun hasFailedMessages(key: String): Boolean = transaction {
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
            it[FailedMessagesTable.messageKeyBytes] = keyBytes
            it[FailedMessagesTable.messageValue] = value
            it[FailedMessagesTable.failureReason] = reason
            it[FailedMessagesTable.topic] = repositoryTopic
        }
    }

    // Henter en batch med meldinger klare for reprosessering
    fun getBatchToRetry(limit: Int): List<FailedMessage> = transaction {
        FailedMessagesEntity
            .find { FailedMessagesTable.topic eq repositoryTopic }
            .limit(limit)
            .map { it.toFailedMessage() }
    }

    fun delete(messageId: Long): Unit = transaction {
        FailedMessagesTable.deleteWhere { FailedMessagesTable.id eq messageId }
    }

    fun updateAfterFailedAttempt(messageId: Long, newReason: String): Unit = transaction {
        FailedMessagesTable
            .update({ FailedMessagesTable.id eq messageId })
            {
                with(SqlExpressionBuilder) {
                    it[FailedMessagesTable.retryCount] = retryCount + 1
                    it[FailedMessagesTable.lastAttemptTimestamp] = OffsetDateTime.now()
                    it[FailedMessagesTable.failureReason] = newReason
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