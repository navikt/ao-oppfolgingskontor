package services

import db.table.IdentMappingTable
import db.table.IdentMappingTable.historisk
import db.table.IdentMappingTable.identType
import db.table.IdentMappingTable.internIdent
import db.table.IdentMappingTable.updatedAt
import db.table.InternIdentSequence
import db.table.nextValueOf
import kafka.consumers.NyIdent
import no.nav.db.AktorId
import no.nav.db.Dnr
import no.nav.db.Fnr
import no.nav.db.Ident
import no.nav.db.Npid
import no.nav.http.client.IdentFunnet
import no.nav.http.client.IdentOppslagFeil
import no.nav.http.client.IdentResult
import no.nav.http.client.IdenterFunnet
import no.nav.http.client.IdenterResult
import no.nav.http.client.finnForetrukketIdent
import no.nav.http.graphql.generated.client.hentfnrquery.IdentInformasjon
import no.nav.kafka.retry.library.internal.Success
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.batchUpsert
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.ZonedDateTime

class IdentService(
    val alleIdenterProvider: suspend (aktorId: String) -> IdenterResult,
) {
    private val log = LoggerFactory.getLogger(IdentService::class.java)

    suspend fun hentForetrukketIdentFor(ident: Ident): IdentResult {
        val lokalIdentResult = hentLokalIdent(ident)
        val lokaltLagretIdent = when {
            lokalIdentResult.isSuccess -> lokalIdentResult.getOrNull()
            else -> {
                val error = lokalIdentResult.exceptionOrNull()
                log.error("Feil ved oppslag på lokalt lagret ident: ${error?.message}", error)
                IdentOppslagFeil("Feil ved oppslag på lokalt lagret ident: ${error?.message}")
            }
        }
        if (lokaltLagretIdent != null) return lokaltLagretIdent
        return hentAlleIdenterOgOppdaterMapping(ident)
            .finnForetrukketIdent()
    }

    /* Tenkt kalt ved endring på aktor-v2 topic (endring i identer) */
    suspend fun hånterEndringPåIdenter(ident: Ident): IdenterResult = hentAlleIdenterOgOppdaterMapping(ident)

    suspend fun hånterEndringPåIdenter(ident: Ident, nyeIdenter: List<NyIdent>) {
        val gamleIdenter = transaction {
            hentIdentMappinger(ident, includeHistorisk = true)
        }
        gamleIdenter.finnEndringer(nyeIdenter)
    }

    private fun hentLokalIdent(ident: Ident): Result<IdentFunnet?> {
        return runCatching {
            val identMappings = hentIdentMappinger(ident)
            when {
                identMappings.isNotEmpty() -> {
                    val foretrukketIdent = identMappings
                        .filter { it !is AktorId }
                        .map { Ident.of(it.value) }
                        .minByOrNull {
                            when (it) {
                                is Fnr -> 1
                                is Dnr -> 2
                                is Npid -> 3
                                else -> 5 // Aktørid eller annen ukjent ident
                            }
                        }
                    return Result.success(IdentFunnet(foretrukketIdent!!))
                }
                else -> return Result.success(null)
            }
        }
    }

    private suspend fun hentAlleIdenterOgOppdaterMapping(ident: Ident): IdenterResult {
        return when(val identer = alleIdenterProvider(ident.value)) {
            is IdenterFunnet -> {
                oppdaterAlleIdentMappinger(identer)
                identer
            }
            else -> identer
        }
    }

    fun Transaction.getInternId(eksitrerendeInternIder: List<Long>): Long {
        return if (eksitrerendeInternIder.isNotEmpty()) {
            if (eksitrerendeInternIder.distinct().size != 1)
                throw IllegalStateException("Fant flere forskjellige intern-id-er på en ident-liste-response fra PDL")
            else eksitrerendeInternIder.first()
        } else {
            nextValueOf(InternIdentSequence)
        }
    }

    private fun oppdaterAlleIdentMappinger(identer: IdenterFunnet) {
        try {
            val eksitrerendeInternIder = transaction {
                IdentMappingTable
                    .select(internIdent)
                    .where { IdentMappingTable.id inList(identer.identer.map { it.ident }) }
                    .map { it[internIdent] }
            }

            transaction {
                val internId = getInternId(eksitrerendeInternIder)
                IdentMappingTable.batchUpsert(identer.identer) {
                    this[IdentMappingTable.id] = it.ident
                    this[identType] = it.toIdentType()
                    this[internIdent] = internId
                    this[historisk] = it.historisk
                    this[updatedAt] = ZonedDateTime.now().toOffsetDateTime()
                }
            }
        } catch (e: Throwable) {
            // TODO: Ikke sluk denne feilen(?)
            log.error("Kunne ikke lagre ident-mapping ${e.message}", e)
        }
    }

    private fun IdentInformasjon.toIdentType(): String {
        val ident = Ident.of(this.ident)
        return when (ident) {
            is AktorId -> "AKTOR_ID"
            is Dnr -> "DNR"
            is Fnr -> "FNR"
            is Npid -> "NPID"
        }
    }

    private fun hentIdentMappinger(identInput: Ident, includeHistorisk: Boolean = false): List<Ident> = transaction {
        val identMappingAlias = IdentMappingTable.alias("ident_mapping_alias")

        IdentMappingTable.join(
            identMappingAlias,
            JoinType.INNER,
            onColumn = internIdent,
            otherColumn = identMappingAlias[internIdent]
        )
            .select(identMappingAlias[IdentMappingTable.id], identMappingAlias[identType], identMappingAlias[historisk])
            .where { (IdentMappingTable.id eq identInput.value) }
            .let { query ->
                if (!includeHistorisk) {
                    query.andWhere { identMappingAlias[historisk] eq false }
                } else query
            }
            .map {
                val id = it[identMappingAlias[IdentMappingTable.id]]
                when (val identType = it[identMappingAlias[identType]]) {
                    "FNR" -> Fnr(id.value)
                    "NPID" -> Npid(id.value)
                    "DNR" -> Dnr(id.value)
                    "AKTOR_ID" -> AktorId(id.value)
                    else -> throw IllegalArgumentException("Ukjent identType: $identType")
                        .also { log.error(it.message, it) }
                }
            }
    }
}

sealed class IdentEndring
sealed class NyIdent: IdentEndring()
sealed class BleHistorisk: IdentEndring()
sealed class BleSlettet: IdentEndring()

fun List<Ident>.finnEndringer(nyeIdenter: List<NyIdent>): List<IdentEndring> {

}