package no.nav.db.table

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentDateTime
import org.jetbrains.exposed.sql.kotlin.datetime.datetime

object KontorhistorikkTable : IntIdTable("kontorhistorikk", "id") {
    val fnr = char("fnr", 11) // VARCHAR(11) PRIMARY KEY,
    val kontorId = char("kontorid", 4) // VARCHAR(4),
    val endretAv = varchar("endretav", 20) // VARCHAR(20),
    val endretAvType = varchar("endretavtype", 20) // VARCHAR(20),
    val kontorendringstype = varchar("kontorendringstype", 255) // VARCHAR(20),
    val createdAt = datetime("createdat").defaultExpression(CurrentDateTime) // TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
}