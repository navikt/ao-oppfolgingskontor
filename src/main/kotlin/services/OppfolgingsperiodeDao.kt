package no.nav.services

import java.time.ZonedDateTime
import no.nav.db.Ident
import no.nav.db.entity.OppfolgingsperiodeEntity
import no.nav.db.finnForetrukketIdent
import no.nav.db.table.KontorhistorikkTable
import no.nav.db.table.OppfolgingsperiodeTable
import no.nav.db.table.OppfolgingsperiodeTable.oppfolgingsperiodeId
import no.nav.domain.OppfolgingsperiodeId
import no.nav.domain.externalEvents.OppfolgingsperiodeStartet
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert
import org.slf4j.LoggerFactory
import utils.Outcome
import java.time.OffsetDateTime
import java.time.ZoneOffset

sealed class OppfolgingsperiodeOppslagResult()
data class AktivOppfolgingsperiode(val fnr: Ident, val periodeId: OppfolgingsperiodeId, val startDato: OffsetDateTime) : OppfolgingsperiodeOppslagResult()
object NotUnderOppfolging : OppfolgingsperiodeOppslagResult()
data class OppfolgingperiodeOppslagFeil(val message: String) : OppfolgingsperiodeOppslagResult()

object OppfolgingsperiodeDao {
    private val log = LoggerFactory.getLogger(this::class.java)

    fun saveOppfolgingsperiode(fnr: Ident, startDato: ZonedDateTime, oppfolgingsperiodeId: OppfolgingsperiodeId) {
        transaction {
            OppfolgingsperiodeTable.upsert {
                it[id] = fnr.value
                it[this.startDato] = startDato.toOffsetDateTime()
                it[this.oppfolgingsperiodeId] = oppfolgingsperiodeId.value
                it[this.updatedAt] = OffsetDateTime.now(ZoneOffset.systemDefault())
            }
        }
    }

    fun finnesPeriode(oppfolgingsPeriode: OppfolgingsperiodeId): Boolean {
        return transaction {
            OppfolgingsperiodeTable
                .select(OppfolgingsperiodeTable.id, oppfolgingsperiodeId)
                .where { oppfolgingsperiodeId eq oppfolgingsPeriode.value }
                .firstOrNull() != null
        }
    }

    fun harBruktPeriodeTidligere(ident: Ident, periodeId: OppfolgingsperiodeId): Outcome<Boolean> {
        return try {
            transaction {
                val tidligereEntries = KontorhistorikkTable.select(KontorhistorikkTable.ident)
                    .where { KontorhistorikkTable.ident eq ident.value and (KontorhistorikkTable.oppfolgingsperiodeId eq periodeId.value) }
                    .map { it }
                    .size
                Outcome.Success((tidligereEntries) > 0)
            }
        } catch (e: Exception) {
            log.error("Kunne ikke sjekke om oppfølgingsperiode allerede er brukt for å gjøre kontortilordning")
            return Outcome.Failure(e)
        }
    }

    fun deleteOppfolgingsperiode(oppfolgingsperiodeId: OppfolgingsperiodeId): Int {
        return transaction {
            val deletedRows = OppfolgingsperiodeTable.deleteWhere { OppfolgingsperiodeTable.oppfolgingsperiodeId eq oppfolgingsperiodeId.value }
            if (deletedRows > 0) {
                log.info("Deleted oppfolgingsperiode")
            } else {
                log.info("Attempted to delete oppfolgingsperiode but no record was found")
            }
            return@transaction deletedRows
        }
    }

    fun harNyerePeriodePåIdent(oppfolgingsperiode: OppfolgingsperiodeStartet): Boolean {
        return transaction {
            val eksisterendeStartDato = OppfolgingsperiodeTable.select(OppfolgingsperiodeTable.startDato)
                .where { (OppfolgingsperiodeTable.id eq oppfolgingsperiode.fnr.value) and (oppfolgingsperiodeId neq oppfolgingsperiode.periodeId.value) }
                .map { row -> row[OppfolgingsperiodeTable.startDato] }
                .firstOrNull()
            if (eksisterendeStartDato != null) {
                return@transaction eksisterendeStartDato.isAfter(oppfolgingsperiode.startDato.toOffsetDateTime())
            }
            return@transaction false
        }
    }

    fun getCurrentOppfolgingsperiode(identer: List<Ident>): OppfolgingsperiodeOppslagResult {
        val oppfolgingsperioder = transaction {
            OppfolgingsperiodeEntity.find { OppfolgingsperiodeTable.id inList identer.map { it.value } }.toList()
        }
        return when (oppfolgingsperioder.size) {
            0 -> NotUnderOppfolging
            1 -> AktivOppfolgingsperiode(
                identer.finnForetrukketIdent()
                    ?: throw IllegalStateException("Kan ikke ha oppfølgingsperiode når det ikke finnes noen foretrukken ident"),
                OppfolgingsperiodeId(oppfolgingsperioder.first().oppfolgingsperiodeId),
                oppfolgingsperioder.first().startDato
            )
            else -> OppfolgingperiodeOppslagFeil("Fant flere oppfølgingsperioder (${oppfolgingsperioder.size}). Dnr til fnr?")
        }
    }
}
