package no.nav.services

import arrow.core.Either
import db.table.AlternativAoKontorTable
import eventsLogger.BigQueryClient
import no.nav.db.IdentSomKanLagres
import no.nav.db.table.ArbeidsOppfolgingKontorTable
import no.nav.db.table.ArenaKontorTable
import no.nav.db.table.GeografiskTilknytningKontorTable
import no.nav.db.table.KontorhistorikkTable
import no.nav.domain.System
import no.nav.domain.events.AOKontorEndret
import no.nav.domain.events.ArenaKontorEndret
import no.nav.domain.events.GTKontorEndret
import no.nav.domain.events.KontorEndretEvent
import no.nav.kafka.consumers.KontorEndringer
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert
import java.time.ZonedDateTime

object KontorTilordningService {
    lateinit var bigQueryClient: BigQueryClient

    fun tilordneKontor(kontorEndringer: KontorEndringer, brukAoRuting: Boolean) {
        kontorEndringer.aoKontorEndret?.let { tilordneKontor(it, brukAoRuting) }
        kontorEndringer.arenaKontorEndret?.let { tilordneKontor(it, brukAoRuting) }
        kontorEndringer.gtKontorEndret?.let { tilordneKontor(it, brukAoRuting) }
    }
    fun tilordneKontor(kontorEndring: KontorEndretEvent, brukAoRuting: Boolean) {
        val kontorTilhorighet = kontorEndring.tilordning
        transaction {
            kontorEndring.logg()
            when (kontorEndring) {
                is AOKontorEndret -> {
                    if(brukAoRuting) {
                        val entryId = settKontorIHistorikk(kontorEndring)
                        ArbeidsOppfolgingKontorTable.upsert {
                            it[kontorId] = kontorTilhorighet.kontorId.id
                            it[id] = kontorTilhorighet.fnr.value
                            it[endretAv] = kontorEndring.registrant.getIdent()
                            it[endretAvType] = kontorEndring.registrant.getType()
                            it[updatedAt] = ZonedDateTime.now().toOffsetDateTime()
                            it[historikkEntry] = entryId.value
                        }
                    } else
                    {
                        AlternativAoKontorTable.insert {
                            it[fnr] = kontorTilhorighet.fnr.value
                            it[kontorId] = kontorTilhorighet.kontorId.id
                            it[endretAv] = System().getIdent()
                            it[endretAvType] = System().getType()
                            it[kontorendringstype] = kontorEndring.kontorEndringsType().name
                            it[updatedAt] = ZonedDateTime.now().toOffsetDateTime()
                        }
                    }
                }
                is ArenaKontorEndret -> {
                    val entryId = settKontorIHistorikk(kontorEndring)
                    if(!brukAoRuting){
                        ArbeidsOppfolgingKontorTable.upsert {
                            it[kontorId] = kontorTilhorighet.kontorId.id
                            it[id] = kontorTilhorighet.fnr.value
                            it[endretAv] = System().getIdent()
                            it[endretAvType] = System().getType()
                            it[updatedAt] = ZonedDateTime.now().toOffsetDateTime()
                            it[historikkEntry] = entryId.value
                        }
                    }
                    ArenaKontorTable.upsert {
                        it[kontorId] = kontorTilhorighet.kontorId.id
                        it[id] = kontorTilhorighet.fnr.value
                        it[updatedAt] = ZonedDateTime.now().toOffsetDateTime()
                        it[sistEndretDatoArena] = kontorEndring.sistEndretDatoArena
                        it[historikkEntry] = entryId
                    }
                }
                is GTKontorEndret -> {
                    val entryId = settKontorIHistorikk(kontorEndring)
                    GeografiskTilknytningKontorTable.upsert {
                        it[kontorId] = kontorTilhorighet.kontorId.id
                        it[id] = kontorTilhorighet.fnr.value
                        it[gt] = kontorEndring.gt()
                        it[gtType] = kontorEndring.gtType()
                        it[updatedAt] = ZonedDateTime.now().toOffsetDateTime()
                        it[historikkEntry] = entryId.value
                    }
                }
            }
        }
    }

    fun slettArbeidsoppfølgingskontorTilordning(ident: IdentSomKanLagres): Either<Throwable, Unit> {
        return Either.catch {
            transaction {
                val antallRaderSlettet = ArbeidsOppfolgingKontorTable.deleteWhere { id eq ident.value }
                when (antallRaderSlettet) {
                    0 -> throw Exception("Fant ingen arbeidsoppfølgingskontortilordning å slette.")
                    1 -> Unit
                    else -> throw Exception("Fant flere arbeidsoppfølgingskontortilordninger å slette, slettet $antallRaderSlettet rader.")
                }
            }
        }
    }

    private fun settKontorIHistorikk(
        kontorEndring: KontorEndretEvent
    ): EntityID<Int> {
        val historikkInnslag = kontorEndring.toHistorikkInnslag()
        bigQueryClient.loggSattKontorEvent(
            historikkInnslag.kontorId.id,
            historikkInnslag.kontorendringstype,
            historikkInnslag.kontorType
        )
        return KontorhistorikkTable.insert {
            it[kontorId] = historikkInnslag.kontorId.id
            it[ident] = historikkInnslag.ident.value
            it[endretAv] = historikkInnslag.registrant.getIdent()
            it[endretAvType] = historikkInnslag.registrant.getType()
            it[kontorendringstype] = historikkInnslag.kontorendringstype.name
            it[kontorType] = historikkInnslag.kontorType.name
            it[oppfolgingsperiodeId] = historikkInnslag.oppfolgingId.value
        }[KontorhistorikkTable.id]
    }
}