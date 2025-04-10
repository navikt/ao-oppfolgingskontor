package no.nav.db.entity

import no.nav.db.table.KontorhistorikkTable
import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.ImmutableEntityClass
import org.jetbrains.exposed.dao.id.EntityID

class SistEndretKontorEntity(id: EntityID<Int>) : Entity<Int>(id) {
    companion object : ImmutableEntityClass<Int, SistEndretKontorEntity>(KontorhistorikkTable)

    val fnr by KontorhistorikkTable.fnr
    val kontorId by KontorhistorikkTable.kontorId
    val createdAt by KontorhistorikkTable.createdAt
}