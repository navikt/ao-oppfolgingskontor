package no.nav.db.table

import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.sql.kotlin.datetime.timestampWithTimeZone

object KontorNavnTable: IdTable<String>("kontor_navn") {
    override val id = varchar("kontor_navn", 50).entityId() // VARCHAR(11) PRIMARY KEY,
    override val primaryKey = PrimaryKey(id) // PRIMARY KEY (fnr),
    val kontorId = char("kontor_id", 4)
    val navn =
    val updatedAt = timestampWithTimeZone("updated_at")
        .defaultExpression(CurrentTimestampWithTimeZone) // TIMESTAMPTZ DEFAULT NOW()
}