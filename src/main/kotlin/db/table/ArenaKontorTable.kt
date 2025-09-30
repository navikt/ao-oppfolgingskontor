package no.nav.db.table

import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.sql.kotlin.datetime.timestampWithTimeZone

object ArenaKontorTable: IdTable<String>("arenakontor") {
    override val id = char("fnr", 11).entityId() // VARCHAR(11) PRIMARY KEY,
    override val primaryKey = PrimaryKey(id) // PRIMARY KEY (fnr),
    val kontorId = char("kontor_id", 4)
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestampWithTimeZone) // TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    val updatedAt = timestampWithTimeZone("updated_at").defaultExpression(CurrentTimestampWithTimeZone) // TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    val sistEndretDatoArena = timestampWithTimeZone("sist_endret_dato_arena")
    /* Noen få arena-kontor (alle fra 2025-08-20) har ikke fått historikk entry, derfor må denne være nullable */
    val historikkEntry = reference("historikk_entry", KontorhistorikkTable.id).nullable()
}