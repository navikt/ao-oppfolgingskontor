package no.nav.db.table

import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.sql.kotlin.datetime.timestampWithTimeZone

object ArbeidsOppfolgingKontorTable: IdTable<String>("arbeidsoppfolgingskontor") {
    override val id = varchar("fnr", 11).entityId() // VARCHAR(11) PRIMARY KEY,
    override val primaryKey = PrimaryKey(ArenaKontorTable.id) // PRIMARY KEY (fnr),
    val kontorId = varchar("kontorId", 4) // VARCHAR(4),
    val endretAv = varchar("endretAv", 20) // VARCHAR(20),
    val endretAvType = varchar("endretAvType", 20) // VARCHAR(20),
    val createdAt = timestampWithTimeZone("createdAt").defaultExpression(CurrentTimestampWithTimeZone) // TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    val updatedAt = timestampWithTimeZone("updatedAt").defaultExpression(CurrentTimestampWithTimeZone) // TIMESTAMP DEFAULT CURRENT_TIMESTAMP
}