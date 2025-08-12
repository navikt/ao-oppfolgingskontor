package no.nav.kafka.retry.library.internal

import db.table.KafkaOffsetTable
import no.nav.db.table.FailedMessagesEntity
import no.nav.db.table.FailedMessagesTable
import no.nav.db.table.FailedMessagesTable.failureReason
import no.nav.db.table.FailedMessagesTable.lastAttemptTimestamp
import no.nav.db.table.FailedMessagesTable.messageKeyBytes
import no.nav.db.table.FailedMessagesTable.messageKeyText
import no.nav.db.table.FailedMessagesTable.messageValue
import no.nav.db.table.FailedMessagesTable.queueTimestamp
import no.nav.db.table.FailedMessagesTable.retryCount
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.vendors.ForUpdateOption
import java.time.OffsetDateTime

class RetryableRepository(val repositoryTopic: String) {

    fun hasFailedMessages(key: String): Boolean = transaction {
        if (key.isEmpty()) throw IllegalArgumentException("Key cannot be empty")
        if (key.isBlank()) throw IllegalArgumentException("Key cannot be blank")
        if (key.contains('\u0000')) throw IllegalArgumentException("Key: <${key}> contained contain null characters topic $repositoryTopic ")
        !FailedMessagesTable
            .selectAll()
            .where { (FailedMessagesTable.topic eq repositoryTopic) and (messageKeyText eq key) }
            .limit(1)
            .empty()
    }

    fun enqueue(keyString: String, keyBytes: ByteArray, value: ByteArray, reason: String): Long = transaction {
        val x = FailedMessagesTable.insertAndGetId {
            it[messageKeyText] = keyString
            it[messageKeyBytes] = keyBytes
            it[messageValue] = value
            it[failureReason] = reason
            it[topic] = repositoryTopic
        }
        x.value
    }

    fun enqueue(keyString: String, keyBytes: ByteArray, value: ByteArray, reason: String, humanReadableValue: String?): Long = transaction {
        FailedMessagesTable.insertAndGetId {
            it[messageKeyText] = keyString
            it[messageKeyBytes] = keyBytes
            it[messageValue] = value
            it[failureReason] = reason
            it[topic] = repositoryTopic
            it[this.humanReadableValue] = humanReadableValue
        }.value
    }

    // Henter en batch med meldinger klare for reprosessering
    fun getBatchToRetry(limit: Int): List<FailedMessage> = transaction {
        FailedMessagesTable
            .selectAll()
            .where { FailedMessagesTable.topic eq repositoryTopic }
            .forUpdate(ForUpdateOption.ForUpdate)
            .orderBy(FailedMessagesTable.id to SortOrder.ASC)
            .limit(limit)
            .map { row ->
                FailedMessage(
                    id = row[FailedMessagesTable.id].value,
                    messageKeyText = row[messageKeyText],
                    messageKeyBytes = row[messageKeyBytes],
                    messageValue = row[messageValue],
                    queueTimestamp = row[queueTimestamp],
                    lastAttemptTimestamp = row[lastAttemptTimestamp],
                    retryCount = row[retryCount],
                    failureReason = row[failureReason],
                )
            }
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

    fun saveOffset(partition: Int, offset: Long) = transaction {
        KafkaOffsetTable.insert {
            it[KafkaOffsetTable.topic] = repositoryTopic
            it[KafkaOffsetTable.partition] = partition
            it[KafkaOffsetTable.offset] = offset
        }
    }

    fun getOffset(partition: Int): Long = transaction {
         KafkaOffsetTable
            .selectAll()
            .where { (KafkaOffsetTable.partition eq partition) and (KafkaOffsetTable.topic eq repositoryTopic) }
            .singleOrNull()
            .let { row ->
                row?.get(KafkaOffsetTable.offset) ?: 0
            }
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