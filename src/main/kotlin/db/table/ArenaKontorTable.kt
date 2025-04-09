package no.nav.db.table

import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentDateTime
import org.jetbrains.exposed.sql.kotlin.datetime.datetime

object ArenaKontorTable: IdTable<String>("arenakontor") {
    override val id = char("fnr", 11).entityId() // VARCHAR(11) PRIMARY KEY,
    override val primaryKey = PrimaryKey(id) // PRIMARY KEY (fnr),
    val kontorId = char("kontorid", 4) // VARCHAR(4),
    val endretAv = varchar("endretav", 20) // VARCHAR(20),
    val endretAvType = varchar("endretavtype", 20) // VARCHAR(20),
    val createdAt = datetime("createdat").defaultExpression(CurrentDateTime) // TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    val updatedAt = datetime("updatedat").defaultExpression(CurrentDateTime) // TIMESTAMP DEFAULT CURRENT_TIMESTAMP
}