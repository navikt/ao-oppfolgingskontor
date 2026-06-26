package no.nav.services

import arrow.core.Either
import eventsLogger.KontorTypeForBigQuery
import eventsLogger.LoggSattKontorEvent
import no.nav.db.table.ArbeidsOppfolgingKontorTable
import no.nav.db.table.GeografiskTilknytningKontorTable
import no.nav.db.table.KontorhistorikkTable
import no.nav.domain.OppfolgingsperiodeId
import no.nav.domain.events.AOKontorEndret
import no.nav.domain.events.GTKontorEndret
import no.nav.domain.events.KontorEndretEvent
import no.nav.kafka.consumers.KontorEndringer
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.ZonedDateTime

typealias TilordneKontor = (kontorEndringer: KontorEndretEvent) -> Unit

class KontorTilordningService(
    private val loggSattKontorEvent: LoggSattKontorEvent
) {

    fun tilordneKontor (kontorEndringer: KontorEndringer) {
        kontorEndringer.aoKontorEndret?.let { tilordneKontor(it) }
        kontorEndringer.gtKontorEndret?.let { tilordneKontor(it) }
    }
    fun tilordneKontor(kontorEndring: KontorEndretEvent) {
        val kontorTilhorighet = kontorEndring.tilordning
        transaction {
            kontorEndring.logg()
            when (kontorEndring) {
                is AOKontorEndret -> {
                    val forrigeKontorId = hentGjeldendeKontorIdFraArbeidsoppfolgingskontor(kontorTilhorighet.fnr.value)
                    val entryId = settKontorIHistorikk(kontorEndring)
                    ArbeidsOppfolgingKontorTable.upsert {
                        it[kontorId] = kontorTilhorighet.kontorId.id
                        it[id] = kontorTilhorighet.fnr.value
                        it[endretAv] = kontorEndring.registrant.getIdent()
                        it[endretAvType] = kontorEndring.registrant.getType()
                        it[updatedAt] = ZonedDateTime.now().toOffsetDateTime()
                        it[historikkEntry] = entryId.value
                        it[oppfolgingsperiodeId] = kontorEndring.tilordning.oppfolgingsperiodeId.value
                    }
                    loggSattKontorEvent(
                        kontorTilhorighet.kontorId.id,
                        forrigeKontorId,
                        kontorEndring.kontorEndringsType(),
                        KontorTypeForBigQuery.ARBEIDSOPPFOLGINGSKONTOR
                    )
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

    fun slettArbeidsoppfølgingskontorTilordning(oppfolgingsperiodeIdSomSkalSlettes: OppfolgingsperiodeId): Either<Throwable, Int> {
        return Either.catch {
            transaction {
                ArbeidsOppfolgingKontorTable.deleteWhere { oppfolgingsperiodeId eq oppfolgingsperiodeIdSomSkalSlettes.value }
            }
        }
    }

    private fun settKontorIHistorikk(
        kontorEndring: KontorEndretEvent
    ): EntityID<Int> {
        val historikkInnslag = kontorEndring.toHistorikkInnslag()
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

    private fun hentGjeldendeKontorIdFraArbeidsoppfolgingskontor(fnr: String): String? {
        return ArbeidsOppfolgingKontorTable
            .select(ArbeidsOppfolgingKontorTable.kontorId)
            .where { ArbeidsOppfolgingKontorTable.id eq fnr }
            .map { it[ArbeidsOppfolgingKontorTable.kontorId] }
            .firstOrNull()
    }
}