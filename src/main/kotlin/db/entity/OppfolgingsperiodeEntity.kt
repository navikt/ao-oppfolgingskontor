package no.nav.db.entity

import no.nav.db.table.OppfolgingsperiodeTable
import org.jetbrains.exposed.v1.dao.Entity
import org.jetbrains.exposed.v1.dao.ImmutableEntityClass
import org.jetbrains.exposed.v1.core.dao.id.EntityID

class OppfolgingsperiodeEntity(id: EntityID<String>) : Entity<String>(id) {
    companion object :
            ImmutableEntityClass<String, OppfolgingsperiodeEntity>(OppfolgingsperiodeTable)

    val fnr by OppfolgingsperiodeTable.id
    val startDato by OppfolgingsperiodeTable.startDato
    val createdAt by OppfolgingsperiodeTable.createdAt
    val updatedAt by OppfolgingsperiodeTable.updatedAt
    val oppfolgingsperiodeId by OppfolgingsperiodeTable.oppfolgingsperiodeId
}
