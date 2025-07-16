package no.nav.services

import no.nav.db.table.ArbeidsOppfolgingKontorTable
import no.nav.db.table.ArenaKontorTable
import no.nav.db.table.GeografiskTilknytningKontorTable
import no.nav.db.table.KontorhistorikkTable
import no.nav.db.table.KontorhistorikkTable.fnr
import no.nav.domain.events.AOKontorEndret
import no.nav.domain.events.ArenaKontorEndret
import no.nav.domain.events.GTKontorEndret
import no.nav.domain.events.KontorEndretEvent
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.statements.InsertStatement
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert
import java.time.ZonedDateTime

object KontorTilordningService {
    fun tilordneKontor(kontorEndring: KontorEndretEvent) {
        val kontorTilhorighet = kontorEndring.tilordning
        transaction {
            kontorEndring.logg()
            when (kontorEndring) {
                is AOKontorEndret -> {
                    ArbeidsOppfolgingKontorTable.upsert {
                        it[kontorId] = kontorTilhorighet.kontorId.id
                        it[fnr] = kontorTilhorighet.fnr.value
                        it[endretAv] = kontorEndring.registrant.getIdent()
                        it[endretAvType] = kontorEndring.registrant.getType()
                        it[updatedAt] = ZonedDateTime.now().toOffsetDateTime()
                    }
                }
                is ArenaKontorEndret -> {
                    ArenaKontorTable.upsert {
                        it[kontorId] = kontorTilhorighet.kontorId.id
                        it[fnr] = kontorTilhorighet.fnr.value
                        it[updatedAt] = ZonedDateTime.now().toOffsetDateTime()
                        it[sistEndretDatoArena] = kontorEndring.sistEndretDatoArena
                    }
                }
                is GTKontorEndret -> {
                    GeografiskTilknytningKontorTable.upsert {
                        it[kontorId] = kontorTilhorighet.kontorId.id
                        it[fnr] = kontorTilhorighet.fnr.value
                        it[updatedAt] = ZonedDateTime.now().toOffsetDateTime()
                    }
                }
            }
            settKontorIHistorikk(kontorEndring)
        }
    }

    private fun settKontorIHistorikk(
        kontorEndring: KontorEndretEvent
    ): InsertStatement<Number> {
        val historikkInnslag = kontorEndring.toHistorikkInnslag()
        return KontorhistorikkTable.insert {
            it[kontorId] = historikkInnslag.kontorId.id
            it[fnr] = historikkInnslag.ident.value
            it[endretAv] = historikkInnslag.registrant.getIdent()
            it[endretAvType] = historikkInnslag.registrant.getType()
            it[kontorendringstype] = historikkInnslag.kontorendringstype.name
            it[kontorType] = historikkInnslag.kontorType.name
            it[oppfolgingsperiodeId] = historikkInnslag.oppfolgingId.value
        }
    }
}