package no.nav.db.entity

import no.nav.db.table.ArenaKontorTable
import org.jetbrains.exposed.dao.ImmutableEntityClass
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.id.EntityID

class ArenaKontorEntity(id: EntityID<Int>): IntEntity(id) {
    companion object : ImmutableEntityClass<Int, ArenaKontorEntity>(ArenaKontorTable)
    val fnr by ArenaKontorTable.fnr
    val kontorId by ArenaKontorTable.kontorId
    val endretAv by ArenaKontorTable.endretAv
    val endretAvType by ArenaKontorTable.endretAvType
    val createdAt by ArenaKontorTable.createdAt
    val updatedAt by ArenaKontorTable.updatedAt
}