package db.entity

import db.table.TidligArenaKontorTable
import no.nav.db.table.OppfolgingsperiodeTable
import org.jetbrains.exposed.v1.dao.Entity
import org.jetbrains.exposed.v1.dao.ImmutableEntityClass
import org.jetbrains.exposed.v1.core.dao.id.EntityID

class TidligArenaKontorEntity(id: EntityID<String>) : Entity<String>(id) {
    companion object : ImmutableEntityClass<String, TidligArenaKontorEntity>(TidligArenaKontorTable)
    val fnr by OppfolgingsperiodeTable.id
    val createdAt by TidligArenaKontorTable.createdAt
    val updatedAt by TidligArenaKontorTable.updatedAt
    val kontorId by TidligArenaKontorTable.kontorId
    val sistEndretDato by TidligArenaKontorTable.sisteEndretDato
}
