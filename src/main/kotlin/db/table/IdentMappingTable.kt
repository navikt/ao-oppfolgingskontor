package db.table

import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentTimestampWithTimeZone

object IdentMappingTable: IdTable<String>("ident_mapping") {
    override val id = char("ident", 20).entityId()
    val identType = char("ident_type", 11)
    val historisk = bool("historisk")
    val internIdent = long("intern_ident").autoIncrement("ident_mapping_intern_ident_seq")
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestampWithTimeZone)
    val updatedAt = timestampWithTimeZone("updated_at").defaultExpression(CurrentTimestampWithTimeZone)
}