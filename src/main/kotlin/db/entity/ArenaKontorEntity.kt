package no.nav.db.entity

import no.nav.db.Fnr
import no.nav.db.table.ArenaKontorTable
import no.nav.domain.KontorId
import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.ImmutableEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.transactions.transaction

class ArenaKontorEntity(id: EntityID<String>): Entity<String>(id), KontorEntity {
    companion object : ImmutableEntityClass<String, ArenaKontorEntity>(ArenaKontorTable) {
        fun sisteLagreKontorArenaKontor(fnr: Fnr) = transaction {
            find { ArenaKontorTable.id eq fnr.value }
                .firstOrNull()
        }
    }
    val fnr by ArenaKontorTable.id
    val kontorId by ArenaKontorTable.kontorId
    val endretAv by ArenaKontorTable.endretAv
    val endretAvType by ArenaKontorTable.endretAvType
    val createdAt by ArenaKontorTable.createdAt
    val updatedAt by ArenaKontorTable.updatedAt
    val sistEndretDatoArena by ArenaKontorTable.sistEndretDatoArena

    override fun getKontorId(): KontorId {
        return KontorId(kontorId)
    }
}