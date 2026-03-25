package no.nav.db.table

import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.datetime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.datetime.timestampWithTimeZone

object KontorNavnTable: IdTable<String>("kontornavn") {
    override val id = char("kontor_id", 4).entityId()
    override val primaryKey = PrimaryKey(id)
    val kontorNavn = text("kontor_navn")
    val updatedAt = timestampWithTimeZone("updated_at")
        .defaultExpression(CurrentTimestampWithTimeZone) // TIMESTAMPTZ DEFAULT NOW()
}