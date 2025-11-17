package db.table

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.sql.kotlin.datetime.timestampWithTimeZone

object AlternativAoKontorTable : IntIdTable("alternativ_aokontor", "id") {
    val fnr = varchar("fnr", 11)
    val kontorId = varchar("kontor_id", 4)
    val endretAv = varchar("endret_av", 20)
    val endretAvType = varchar("endret_av_type", 20)
    val kontorendringstype = text("kontorendringstype")
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestampWithTimeZone)
    val updatedAt = timestampWithTimeZone("updated_at").defaultExpression(CurrentTimestampWithTimeZone)
}
