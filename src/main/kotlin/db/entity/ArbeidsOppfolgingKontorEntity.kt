package no.nav.db.entity

import no.nav.db.table.ArbeidsOppfolgingKontorTable
import no.nav.domain.KontorId
import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.ImmutableEntityClass
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.id.EntityID

class ArbeidsOppfolgingKontorEntity(id: EntityID<String>): Entity<String>(id), KontorEntity {
    companion object : ImmutableEntityClass<String, ArbeidsOppfolgingKontorEntity>(ArbeidsOppfolgingKontorTable)
    val fnr by ArbeidsOppfolgingKontorTable.id
    val kontorId by ArbeidsOppfolgingKontorTable.kontorId
    val endretAv by ArbeidsOppfolgingKontorTable.endretAv
    val endretAvType by ArbeidsOppfolgingKontorTable.endretAvType
    val createdAt by ArbeidsOppfolgingKontorTable.createdAt
    val updatedAt by ArbeidsOppfolgingKontorTable.updatedAt
    val historikkEntry by ArbeidsOppfolgingKontorTable.historikkEntry
    val oppfolgingsperiodeId by ArbeidsOppfolgingKontorTable.oppfolgingsperiodeId

    override fun getKontorId(): KontorId {
        return KontorId(kontorId)
    }
}
