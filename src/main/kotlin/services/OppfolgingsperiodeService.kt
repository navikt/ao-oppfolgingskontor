package no.nav.services

import java.time.ZonedDateTime
import no.nav.db.Fnr
import no.nav.db.Ident
import no.nav.db.entity.OppfolgingsperiodeEntity
import no.nav.db.table.OppfolgingsperiodeTable
import no.nav.db.table.OppfolgingsperiodeTable.oppfolgingsperiodeId
import no.nav.domain.OppfolgingsperiodeId
import no.nav.domain.externalEvents.OppfolgingsperiodeStartet
import no.nav.http.client.FnrFunnet
import no.nav.http.client.FnrIkkeFunnet
import no.nav.http.client.FnrOppslagFeil
import no.nav.http.client.FnrResult
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

sealed class OppfolgingsperiodeOppslagResult()
data class AktivOppfolgingsperiode(val fnr: Ident, val periodeId: OppfolgingsperiodeId, val startDato: OffsetDateTime) : OppfolgingsperiodeOppslagResult()
object NotUnderOppfolging : OppfolgingsperiodeOppslagResult()
data class OppfolgingperiodeOppslagFeil(val message: String) : OppfolgingsperiodeOppslagResult()

object OppfolgingsperiodeService {
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

    fun deleteOppfolgingsperiode(oppfolgingsperiodeId: OppfolgingsperiodeId): Int {
        return transaction {
            val deletedRows = OppfolgingsperiodeTable.deleteWhere { OppfolgingsperiodeTable.oppfolgingsperiodeId eq oppfolgingsperiodeId.value }
            if (deletedRows > 0) {
                log.info("Deleted oppfolgingsperiode")
            } else {
                log.warn("Attempted to delete oppfolgingsperiode but no record was found")
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

    fun hasActiveOppfolgingsperiode(fnr: Fnr): Boolean {
        return transaction { OppfolgingsperiodeEntity.findById(fnr.value) != null }
    }

    fun getCurrentOppfolgingsperiode(fnr: Ident) = getCurrentOppfolgingsperiode(FnrFunnet(fnr))
    fun getCurrentOppfolgingsperiode(fnr: FnrResult): OppfolgingsperiodeOppslagResult {
        return try {
            when (fnr) {
                is FnrFunnet -> transaction {
                    val entity = OppfolgingsperiodeEntity.findById(fnr.ident.value)
                    when (entity != null) {
                        true -> AktivOppfolgingsperiode(
                            fnr.ident,
                            OppfolgingsperiodeId(entity.oppfolgingsperiodeId),
                            entity.startDato
                        )
                        else -> NotUnderOppfolging
                    }
                }
                is FnrIkkeFunnet -> OppfolgingperiodeOppslagFeil("Kunne ikke finne oppfølgingsperiode: ${fnr.message}")
                is FnrOppslagFeil -> OppfolgingperiodeOppslagFeil("Kunne ikke finne oppfølgingsperiode: ${fnr.message}")
            }
        } catch (e: Exception) {
            log.error("Error checking oppfolgingsperiode status", e)
            OppfolgingperiodeOppslagFeil("Database error: ${e.message}")
        }
    }
}
