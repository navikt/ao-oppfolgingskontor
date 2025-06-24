package no.nav.db.entity

import no.nav.db.table.KontorhistorikkTable
import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.ImmutableEntityClass
import org.jetbrains.exposed.dao.id.EntityID

class KontorHistorikkEntity(id: EntityID<Int>) : Entity<Int>(id) {
    companion object : ImmutableEntityClass<Int, KontorHistorikkEntity>(KontorhistorikkTable)

    val fnr by KontorhistorikkTable.fnr
    val kontorId by KontorhistorikkTable.kontorId
    val createdAt by KontorhistorikkTable.createdAt
    val endretAv by KontorhistorikkTable.endretAv
    val endretAvType by KontorhistorikkTable.endretAvType
    val kontorendringstype by KontorhistorikkTable.kontorendringstype
    val kontorType by KontorhistorikkTable.kontorType
}
