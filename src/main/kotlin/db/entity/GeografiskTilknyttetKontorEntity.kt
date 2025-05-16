package no.nav.db.entity

import no.nav.db.table.GeografiskTilknytningKontorTable
import no.nav.domain.KontorId
import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.ImmutableEntityClass
import org.jetbrains.exposed.dao.id.EntityID

class GeografiskTilknyttetKontorEntity(id: EntityID<String>): Entity<String>(id), KontorEntity {
    companion object : ImmutableEntityClass<String, GeografiskTilknyttetKontorEntity>(GeografiskTilknytningKontorTable)
    val fnr by GeografiskTilknytningKontorTable.id
    val kontorId by GeografiskTilknytningKontorTable.kontorId
    val createdAt by GeografiskTilknytningKontorTable.createdAt
    val updatedAt by GeografiskTilknytningKontorTable.updatedAt

    override fun getKontorId(): KontorId {
        return KontorId(kontorId)
    }
}
