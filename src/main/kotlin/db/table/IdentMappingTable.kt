package db.table

import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

object IdentMappingTable: IdTable<String>("ident_mapping") {
    val ident = char("ident", 20)
    val identType = char("ident_type", 11)
    val historisk = timestampWithTimeZone("historisk")
    val internIdent = uuid("intern_ident")
    val createdAt = timestampWithTimeZone("created_at")
}