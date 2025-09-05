package no.nav.services

import java.time.ZonedDateTime
import no.nav.db.Fnr
import no.nav.db.Ident
import no.nav.db.entity.OppfolgingsperiodeEntity
import no.nav.db.table.KontorhistorikkTable
import no.nav.db.table.OppfolgingsperiodeTable
import no.nav.db.table.OppfolgingsperiodeTable.oppfolgingsperiodeId
import no.nav.domain.OppfolgingsperiodeId
import no.nav.domain.externalEvents.OppfolgingsperiodeStartet
import no.nav.http.client.IdentFunnet
import no.nav.http.client.IdentIkkeFunnet
import no.nav.http.client.IdentOppslagFeil
import no.nav.http.client.IdentResult
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.exists
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert
import org.slf4j.LoggerFactory
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

    fun harBruktPeriodeTidligere(ident: Ident, periodeId: OppfolgingsperiodeId): Boolean {
        return transaction {
            val tidligereEntries = KontorhistorikkTable.select(KontorhistorikkTable.ident)
                .where { KontorhistorikkTable.ident eq ident.value and (KontorhistorikkTable.oppfolgingsperiodeId eq periodeId.value) }
                .map { it }
                .size
            (tidligereEntries) > 0
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

    fun hasActiveOppfolgingsperiode(fnr: Fnr): Boolean {
        return transaction { OppfolgingsperiodeEntity.findById(fnr.value) != null }
    }

    fun getCurrentOppfolgingsperiode(fnr: Ident) = getCurrentOppfolgingsperiode(IdentFunnet(fnr))
    fun getCurrentOppfolgingsperiode(fnr: IdentResult): OppfolgingsperiodeOppslagResult {
        return try {
            when (fnr) {
                is IdentFunnet -> transaction {
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
                is IdentIkkeFunnet -> OppfolgingperiodeOppslagFeil("Kunne ikke finne oppfølgingsperiode: ${fnr.message}")
                is IdentOppslagFeil -> OppfolgingperiodeOppslagFeil("Kunne ikke finne oppfølgingsperiode: ${fnr.message}")
            }
        } catch (e: Exception) {
            log.error("Error checking oppfolgingsperiode status", e)
            OppfolgingperiodeOppslagFeil("Database error: ${e.message}")
        }
    }
}
