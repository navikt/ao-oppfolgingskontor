package db.entity

import db.table.KafkaOffsetTable
import org.jetbrains.exposed.v1.dao.CompositeEntity
import org.jetbrains.exposed.v1.dao.CompositeEntityClass
import org.jetbrains.exposed.v1.core.dao.id.CompositeID
import org.jetbrains.exposed.v1.core.dao.id.EntityID

class KafkaOffsetEntity(id: EntityID<CompositeID>) : CompositeEntity(id) {
    companion object : CompositeEntityClass<KafkaOffsetEntity>(KafkaOffsetTable)
    val topic by KafkaOffsetTable.topic
    val partition by KafkaOffsetTable.partition
    val offset by KafkaOffsetTable.offset
    val updatedAt by KafkaOffsetTable.updatedAt
}