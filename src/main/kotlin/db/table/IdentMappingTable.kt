package db.table

import no.nav.db.table.ArenaKontorTable
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentTimestampWithTimeZone

object IdentMappingTable: IdTable<String>("ident_mapping") {
    override val id = char("ident", 20).entityId()
    val identType = char("ident_type", 11)
    val historisk = bool("historisk")
    val internIdent = uuid("intern_ident")
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestampWithTimeZone)
    val updatedAt = timestampWithTimeZone("updated_at").defaultExpression(CurrentTimestampWithTimeZone)
}