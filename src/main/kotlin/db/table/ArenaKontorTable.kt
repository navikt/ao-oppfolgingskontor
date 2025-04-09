package no.nav.db.table

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentDateTime
import org.jetbrains.exposed.sql.kotlin.datetime.datetime

object ArenaKontorTable: IntIdTable("arenakontor", "fnr") {
    val fnr = varchar("fnr", 11) // VARCHAR(11) PRIMARY KEY,
    val kontorId = varchar("kontorId", 4) // VARCHAR(4),
    val endretAv = varchar("endretAv", 20) // VARCHAR(20),
    val endretAvType = varchar("endretAvType", 20) // VARCHAR(20),
    val createdAt = datetime("createdAt").defaultExpression(CurrentDateTime) // TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    val updatedAt = datetime("updatedAt").defaultExpression(CurrentDateTime) // TIMESTAMP DEFAULT CURRENT_TIMESTAMP
}