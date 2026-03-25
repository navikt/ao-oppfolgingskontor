package db.table

import org.jetbrains.exposed.v1.core.dao.id.CompositeIdTable
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

object KafkaOffsetTable: CompositeIdTable("kafka_offset") {
    val topic = text("topic")
    val partition = integer("partition")
    val offset = long("offset_value")
    val updatedAt = timestampWithTimeZone("updated_at").defaultExpression(CurrentTimestampWithTimeZone)
    override val primaryKey = PrimaryKey(topic, partition)
}
