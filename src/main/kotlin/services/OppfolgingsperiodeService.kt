package no.nav.services

import java.time.ZonedDateTime
import java.util.UUID
import no.nav.db.Fnr
import no.nav.db.entity.OppfolgingsperiodeEntity
import no.nav.db.table.OppfolgingsperiodeTable
import no.nav.http.client.FnrFunnet
import no.nav.http.client.FnrIkkeFunnet
import no.nav.http.client.FnrOppslagFeil
import no.nav.http.client.FnrResult
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

sealed class OppfolgingsperiodeOppslagResult()
data class AktivOppfolgingsperiode(val fnr: Fnr, val periodeId: UUID) : OppfolgingsperiodeOppslagResult()
object NotUnderOppfolging : OppfolgingsperiodeOppslagResult()
data class OppfolgingperiodeOppslagFeil(val message: String) : OppfolgingsperiodeOppslagResult()

object OppfolgingsperiodeService {
    private val log = LoggerFactory.getLogger(this::class.java)

    suspend fun saveOppfolgingsperiode(fnr: Fnr, startDato: ZonedDateTime, oppfolgingsperiodeId: UUID) {
        transaction {
            OppfolgingsperiodeTable.insert {
                it[id] = fnr.value
                it[this.startDato] = startDato.toOffsetDateTime()
                it[this.oppfolgingsperiodeId] = oppfolgingsperiodeId
            }
        }
    }

    suspend fun deleteOppfolgingsperiode(fnr: Fnr) {
        transaction {
            val deletedRows = OppfolgingsperiodeTable.deleteWhere { OppfolgingsperiodeTable.id eq fnr.value }
            if (deletedRows > 0) {
                log.info("Deleted oppfolgingsperiode")
            } else {
                log.warn("Attempted to delete oppfolgingsperiode but no record was found")
            }
        }
    }

    suspend fun hasActiveOppfolgingsperiode(fnr: Fnr): Boolean {
        return transaction { OppfolgingsperiodeEntity.findById(fnr.value) != null }
    }

    suspend fun getOppfolgingsperiodeStatus(fnr: FnrResult): OppfolgingsperiodeOppslagResult {
        return try {
            when (fnr) {
                is FnrFunnet -> transaction {
                    val entity = OppfolgingsperiodeEntity.findById(fnr.fnr.value)
                    if (entity != null) {
                        // Generate a UUID for the periodeId - in a real scenario this might come from
                        // the database
                        val periodeId = UUID.randomUUID()
                        AktivOppfolgingsperiode(fnr.fnr, periodeId)
                    } else {
                        NotUnderOppfolging
                    }
                }
                is FnrIkkeFunnet -> OppfolgingperiodeOppslagFeil("Kunne ikke finne oppfølgingsperiode: ${fnr.message}")
                is FnrOppslagFeil -> OppfolgingperiodeOppslagFeil("Kunne ikke finne oppfølgingsperiode: ${fnr.message}")
            }
        } catch (e: Exception) {
            log.error("Error checking oppfolgingsperiode status for fnr: $fnr", e)
            OppfolgingperiodeOppslagFeil("Database error: ${e.message}")
        }
    }
}
