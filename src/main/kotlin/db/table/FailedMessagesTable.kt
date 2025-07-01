package no.nav.db.table

import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.kotlin.datetime.timestampWithTimeZone

object FailedMessagesTable: IdTable<Long>("failed_messages") {
    override val id: Column<EntityID<Long>> = long("id").entityId()
    val messageKeyText = varchar("message_key_text", 256)
    val messageKeyBytes = binary("message_key_bytes")
    val messageValue = binary("message_value")
    val queueTimestamp = timestampWithTimeZone("queue_timestamp")
    val lastAttemptTimestamp = timestampWithTimeZone("last_attempt_timestamp").nullable()
    val retryCount = integer("retry_count")
    val failureReason = text("failure_reason")
    val kafkaPartition = text("kafka_partition")
    val kafkaOffset = text("kafka_offset")
    val topic = text("topic")
}

class FailedMessagesEntity(id: EntityID<Long>): LongEntity(id) {
    companion object : LongEntityClass<FailedMessagesEntity>(FailedMessagesTable)
    val messageKeyText by FailedMessagesTable.messageKeyText
    val messageKeyBytes by FailedMessagesTable.messageKeyBytes
    var messageValue by FailedMessagesTable.messageValue
    val queueTimestamp by FailedMessagesTable.queueTimestamp
    val lastAttemptTimestamp by FailedMessagesTable.lastAttemptTimestamp
    val retryCount by FailedMessagesTable.retryCount
    val failureReason by FailedMessagesTable.failureReason
    val kafkaPartition by FailedMessagesTable.kafkaPartition
    val kafkaOffset by FailedMessagesTable.kafkaOffset
    val topic by FailedMessagesTable.topic
}
