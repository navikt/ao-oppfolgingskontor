package db.table

import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

class TidligArenaKontorTable: IdTable<String>("tidlig_arena_kontor") {
    override val id = char("ident", 11).entityId()
    override val primaryKey = PrimaryKey(id)
    val kontorId = varchar("kontor_id", 4)
    val sisteEndretDato = timestampWithTimeZone("siste_endret_dato")
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
}
