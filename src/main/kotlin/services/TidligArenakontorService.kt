package services

import db.table.IdentMappingTable
import db.table.IdentMappingTable.internIdent
import db.table.TidligArenaKontorTable
import no.nav.db.Ident
import no.nav.domain.KontorId
import no.nav.domain.externalEvents.TidligArenaKontor
import no.nav.kafka.consumers.SkalKanskjeUnderOppfolging
import org.jetbrains.exposed.sql.JoinType.INNER
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert
import java.time.ZonedDateTime

class TidligArenakontorService {
    fun lagreTidligArenaKontor(skalKanskjeUnderOppfolging: SkalKanskjeUnderOppfolging) {
        transaction {
            TidligArenaKontorTable.upsert {
                it[id] = skalKanskjeUnderOppfolging.ident.value
                it[kontorId] = skalKanskjeUnderOppfolging.kontorId.id
                it[sisteEndretDato] = skalKanskjeUnderOppfolging.sistEndretDatoArena
                it[updatedAt] = ZonedDateTime.now().toOffsetDateTime()
            }
        }
    }

    fun slettKontor() {

    }

    fun hentArenaKontorOgSlettHvisFunnet(ident: Ident): TidligArenaKontor? {
        return transaction {
            val alleIdenter = IdentMappingTable.alias("alleIdenter")
            val tidligArenaKontorOgIdent = IdentMappingTable
                .join(alleIdenter, INNER, onColumn = internIdent, otherColumn = alleIdenter[internIdent])
                .join(
                    TidligArenaKontorTable,
                    INNER,
                    onColumn = alleIdenter[IdentMappingTable.id],
                    otherColumn = TidligArenaKontorTable.id
                )
                .select(
                    TidligArenaKontorTable.id,
                    TidligArenaKontorTable.kontorId,
                    TidligArenaKontorTable.sisteEndretDato
                )
                .where { IdentMappingTable.id eq ident.value }
                .map { row ->
                    TidligArenaKontor(
                        kontor = KontorId(row[TidligArenaKontorTable.kontorId]),
                        sistEndretDato = row[TidligArenaKontorTable.sisteEndretDato]
                    ) to row[TidligArenaKontorTable.id]
                }.firstOrNull()

            val identSomKontorErLagretPå = tidligArenaKontorOgIdent?.second
            val tidligArenaKontor = tidligArenaKontorOgIdent?.first

            identSomKontorErLagretPå?.let {
                TidligArenaKontorTable.deleteWhere { TidligArenaKontorTable.id eq identSomKontorErLagretPå.value }
            }

            tidligArenaKontor
        }
    }
}