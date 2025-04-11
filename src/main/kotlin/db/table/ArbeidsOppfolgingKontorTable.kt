package no.nav.db.table

import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.sql.kotlin.datetime.timestampWithTimeZone

object ArbeidsOppfolgingKontorTable: IdTable<String>("arbeidsoppfolgingskontor") {
    override val id = varchar("fnr", 11).entityId() // VARCHAR(11) PRIMARY KEY,
    override val primaryKey = PrimaryKey(ArenaKontorTable.id) // PRIMARY KEY (fnr),
    val kontorId = varchar("kontor_id", 4) // VARCHAR(4),
    val endretAv = varchar("endret_av", 20) // VARCHAR(20),
    val endretAvType = varchar("endret_av_type", 20) // VARCHAR(20),
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestampWithTimeZone) // TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    val updatedAt = timestampWithTimeZone("updated_at").defaultExpression(CurrentTimestampWithTimeZone) // TIMESTAMP DEFAULT CURRENT_TIMESTAMP
}