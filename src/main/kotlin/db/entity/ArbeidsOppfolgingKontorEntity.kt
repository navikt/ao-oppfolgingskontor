package no.nav.db.entity

import no.nav.db.table.ArbeidsOppfolgingKontorTable
import org.jetbrains.exposed.dao.ImmutableEntityClass
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.id.EntityID

class ArbeidsOppfolgingKontorEntity(id: EntityID<Int>): IntEntity(id) {
    companion object : ImmutableEntityClass<Int, ArbeidsOppfolgingKontorEntity>(ArbeidsOppfolgingKontorTable)
    val fnr by ArbeidsOppfolgingKontorTable.fnr
    val kontorId by ArbeidsOppfolgingKontorTable.kontorId
    val endretAv by ArbeidsOppfolgingKontorTable.endretAv
    val endretAvType by ArbeidsOppfolgingKontorTable.endretAvType
    val createdAt by ArbeidsOppfolgingKontorTable.createdAt
    val updatedAt by ArbeidsOppfolgingKontorTable.updatedAt
}
