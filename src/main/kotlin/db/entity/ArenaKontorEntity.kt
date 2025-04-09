package no.nav.db.entity

import no.nav.db.table.ArenaKontorTable
import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.ImmutableEntityClass
import org.jetbrains.exposed.dao.id.EntityID

class ArenaKontorEntity(id: EntityID<String>): Entity<String>(id) {
    companion object : ImmutableEntityClass<String, ArenaKontorEntity>(ArenaKontorTable)
    val fnr by ArenaKontorTable.id
    val kontorId by ArenaKontorTable.kontorId
    val endretAv by ArenaKontorTable.endretAv
    val endretAvType by ArenaKontorTable.endretAvType
    val createdAt by ArenaKontorTable.createdAt
    val updatedAt by ArenaKontorTable.updatedAt
}