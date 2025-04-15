package no.nav.db.table

import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.sql.kotlin.datetime.timestampWithTimeZone

object GeografiskTilknytningKontorTable: IdTable<String>("geografisktilknytningkontor") {
    override val id = varchar("fnr", 11).entityId() // VARCHAR(11) PRIMARY KEY,
    override val primaryKey = PrimaryKey(ArenaKontorTable.id) // PRIMARY KEY (fnr),
    val kontorId = varchar("kontor_id", 4) // VARCHAR(4),
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestampWithTimeZone) // TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    val updatedAt = timestampWithTimeZone("updated_at").defaultExpression(CurrentTimestampWithTimeZone)
}