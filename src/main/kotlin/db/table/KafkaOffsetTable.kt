package db.table

import org.jetbrains.exposed.dao.id.CompositeIdTable
import org.jetbrains.exposed.sql.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

object KafkaOffsetTable: CompositeIdTable("kafka_offset") {
    val topic = text("topic")
    val partition = integer("partition")
    val offset = long("offset_value")
    val updatedAt = timestampWithTimeZone("updated_at").defaultExpression(CurrentTimestampWithTimeZone)
    override val primaryKey = PrimaryKey(topic, partition)
}
