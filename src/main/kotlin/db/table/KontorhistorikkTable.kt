package no.nav.db.table

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.sql.kotlin.datetime.timestampWithTimeZone

object KontorhistorikkTable : IntIdTable("kontorhistorikk", "id") {
    val fnr = char("fnr", 11) // VARCHAR(11) PRIMARY KEY,
    val kontorId = char("kontor_id", 4) // VARCHAR(4),
    val endretAv = varchar("endret_av", 20) // VARCHAR(20),
    val endretAvType = varchar("endret_av_type", 20) // VARCHAR(20),
    val kontorendringstype = varchar("kontorendringstype", 255) // VARCHAR(20),
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestampWithTimeZone) // TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
}