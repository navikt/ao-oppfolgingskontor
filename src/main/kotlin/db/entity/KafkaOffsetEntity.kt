package db.entity

import db.table.KafkaOffsetTable
import org.jetbrains.exposed.dao.CompositeEntity
import org.jetbrains.exposed.dao.CompositeEntityClass
import org.jetbrains.exposed.dao.id.CompositeID
import org.jetbrains.exposed.dao.id.EntityID

class KafkaOffsetEntity(id: EntityID<CompositeID>) : CompositeEntity(id) {
    companion object : CompositeEntityClass<KafkaOffsetEntity>(KafkaOffsetTable)
    val topic by KafkaOffsetTable.topic
    val partition by KafkaOffsetTable.partition
    val offset by KafkaOffsetTable.offset
    val updatedAt by KafkaOffsetTable.updatedAt
}