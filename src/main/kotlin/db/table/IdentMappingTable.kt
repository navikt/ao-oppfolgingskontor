package db.table

import org.jetbrains.exposed.sql.Table

object IdentMappingTable: Table("ident_mapping") {
    val aktorId = varchar("aktorid", 20)
    val fnr = varchar("fnr", 20).nullable()
    val npid = varchar("npid", 20).nullable()
}