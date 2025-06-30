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

/*
id BIGSERIAL PRIMARY KEY,                    -- Unik ID for hver rad
message_key_text VARCHAR(255) NOT NULL,           -- For indeksering og feilsøking
message_key_bytes BYTEA,                   -- Den serialiserte nøkkelen fra Kafka-meldingen
message_value BYTEA NOT NULL,                -- Selve meldingen (payload), BYTEA er fleksibelt,
-- Alternativt: JSONB hvis du vet det alltid er JSON
queue_timestamp TIMESTAMPTZ NOT NULL DEFAULT NOW(), -- Tidspunktet meldingen ble lagt i kø, for FIFO-rekkefølge
last_attempt_timestamp TIMESTAMPTZ,          -- Når vi sist prøvde å reprosessere
retry_count INT NOT NULL DEFAULT 0,          -- Antall forsøk på reprosessering
failure_reason TEXT

ADD kafka_partition INT,
ADD kafka_offset INT,
ADD topic TEXT NOT NULL DEFAULT 'default_topic';
*/