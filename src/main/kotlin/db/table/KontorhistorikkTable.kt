package no.nav.db.table

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.sql.kotlin.datetime.timestampWithTimeZone

object KontorhistorikkTable : IntIdTable("kontorhistorikk", "id") {
    val ident = char("ident", 11) // VARCHAR(11) PRIMARY KEY,
    val kontorId = char("kontor_id", 4) // VARCHAR(4),
    val endretAv = varchar("endret_av", 255) // VARCHAR(20),
    val endretAvType = varchar("endret_av_type", 255) // VARCHAR(20),
    val kontorendringstype = varchar("kontorendringstype", 255) // VARCHAR(20),
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestampWithTimeZone) // TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    val kontorType = varchar("kontor_type", 255)
    val oppfolgingsperiodeId = uuid("oppfolgingsperiode_id")
}