package no.nav.db.table

import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.sql.kotlin.datetime.timestampWithTimeZone

object KontorNavnTable: IdTable<String>("kontornavn") {
    override val id = varchar("kontor_id", 50).entityId()
    override val primaryKey = PrimaryKey(id) // PRIMARY KEY (fnr),
//    val kontorId = char("kontor_id", 4)
    val kontorNavn = text("kontor_navn")
    val updatedAt = timestampWithTimeZone("updated_at")
        .defaultExpression(CurrentTimestampWithTimeZone) // TIMESTAMPTZ DEFAULT NOW()
}