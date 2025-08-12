package db.table

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

object KafkaOffsetTable: Table("kafka_offset") {
    val topic = text("topic")
    val partition = integer("partition")
    val offset = long("offset")
    val updatedAt = timestampWithTimeZone("updated_at").defaultExpression(CurrentTimestampWithTimeZone)
}
