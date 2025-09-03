package services

import db.table.IdentMappingTable
import db.table.IdentMappingTable.historisk
import db.table.IdentMappingTable.identType
import db.table.IdentMappingTable.internIdent
import db.table.IdentMappingTable.slettetHosOss
import db.table.IdentMappingTable.updatedAt
import db.table.InternIdentSequence
import db.table.nextValueOf
import kafka.consumers.OppdatertIdent
import no.nav.db.AktorId
import no.nav.db.Dnr
import no.nav.db.Fnr
import no.nav.db.Ident
import no.nav.db.Npid
import no.nav.http.client.IdentFunnet
import no.nav.http.client.IdentOppslagFeil
import no.nav.http.client.IdentResult
import no.nav.http.client.IdenterFunnet
import no.nav.http.client.IdenterIkkeFunnet
import no.nav.http.client.IdenterOppslagFeil
import no.nav.http.client.IdenterResult
import no.nav.http.client.finnForetrukketIdent
import no.nav.http.graphql.generated.client.hentfnrquery.IdentInformasjon
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.batchUpsert
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
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
    suspend fun håndterEndringPåIdenter(ident: Ident): IdenterResult = hentAlleIdenterOgOppdaterMapping(ident)

    suspend fun håndterEndringPåIdenter(aktorId: AktorId, nyeIdenter: List<OppdatertIdent>): Int {
        val eksisterendeIdenter = hentIdentMappinger(aktorId, includeHistorisk = true)
            .let { eksisterende ->
                eksisterende.ifEmpty {
                    val alleIdenter = alleIdenterProvider(aktorId.value)
                    when (alleIdenter) {
                        is IdenterFunnet -> {
                            hentEksisterendeIdenter(alleIdenter.identer.map { Ident.of(it.ident) })
                        }
                        is IdenterIkkeFunnet -> emptyList()
                        is IdenterOppslagFeil -> throw Exception("Klarte ikke hente identer fra PDL: ${alleIdenter.message}")
                    }
                }
            }

        if (eksisterendeIdenter.isEmpty()) return 0

        val endringer = eksisterendeIdenter.finnEndringer(nyeIdenter)
        return transaction {
            IdentMappingTable.batchUpsert(endringer) { row ->
                this[IdentMappingTable.id] = row.ident.value
                this[slettetHosOss] = if (row is BleSlettet) OffsetDateTime.now() else null
                this[historisk] = row.historisk
                this[internIdent] = row.internIdent
                this[identType] = row.ident.toIdentType()
                this[updatedAt] = ZonedDateTime.now().toOffsetDateTime()
            }.size
        }
    }

    fun markerAktorIdSomSlettet(aktorId: AktorId) {
        transaction {
            IdentMappingTable
                .update({ IdentMappingTable.id eq aktorId.value }) {
                    it[slettetHosOss] = ZonedDateTime.now().toOffsetDateTime()
                }
        }
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

    fun Transaction.getOrCreateInternId(eksitrerendeInternIder: List<Long>): Long {
        return if (eksitrerendeInternIder.isNotEmpty()) {
            if (eksitrerendeInternIder.distinct().size != 1)
                throw IllegalStateException("Fant flere forskjellige intern-id-er på en ident-liste-response fra PDL")
            else eksitrerendeInternIder.first()
        } else {
            nextValueOf(InternIdentSequence)
        }
    }

    private fun hentEksisterendeIdenter(identer: List<Ident>): List<IdentInfo> = transaction {
        IdentMappingTable
            .select(internIdent, historisk, identType, IdentMappingTable.id)
            .where { IdentMappingTable.id inList(identer.map { it.value }) }
            .map {
                IdentInfo(
                    Ident.of(it[IdentMappingTable.id].value),
                    it[historisk],
                    it[internIdent]
                )
            }
    }

    private fun oppdaterAlleIdentMappinger(identer: IdenterFunnet) {
        try {
            val eksitrerendeInternIder = hentEksisterendeIdenter(identer.identer.map { Ident.of(it.ident) })
                .map { it.internIdent }

            transaction {
                val internId = getOrCreateInternId(eksitrerendeInternIder)
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

    /**
     * Henter alle koblede identer utenom historiske
     */
    private fun hentIdentMappinger(identInput: Ident): List<Ident> = hentIdentMappinger(identInput, false).map { it.ident }
    private fun hentIdentMappinger(identInput: Ident, includeHistorisk: Boolean): List<IdentInfo> = transaction {
        val identMappingAlias = IdentMappingTable.alias("ident_mapping_alias")

        IdentMappingTable.join(
            identMappingAlias,
            JoinType.INNER,
            onColumn = internIdent,
            otherColumn = identMappingAlias[internIdent]
        )
            .select(identMappingAlias[IdentMappingTable.id],
                identMappingAlias[identType],
                identMappingAlias[historisk],
                identMappingAlias[internIdent])
            .where { (IdentMappingTable.id eq identInput.value) }
            .let { query ->
                if (!includeHistorisk) {
                    query.andWhere { identMappingAlias[historisk] eq false }
                } else query
            }
            .map {
                val id = it[identMappingAlias[IdentMappingTable.id]]
                val historisk = it[identMappingAlias[historisk]]
                val internIdent = it[identMappingAlias[internIdent]]
                val identType = it[identType]
                val ident = id.value.tilIdentType(identType)
                IdentInfo(ident, historisk, internIdent)
            }
    }

    fun hentIdentMappingerBulk(identer: List<Ident>) {
        val stringIdenter = identer.map { it.value }
        val identMappingAlias = IdentMappingTable.alias("ident_mapping_alias")

        IdentMappingTable.join(
            identMappingAlias,
            JoinType.INNER,
            onColumn = internIdent,
            otherColumn = identMappingAlias[internIdent]
        )
            .select(
                IdentMappingTable.id,
                identMappingAlias[IdentMappingTable.id],
                identMappingAlias[identType],
                identMappingAlias[historisk],
                identMappingAlias[internIdent])
            .where { IdentMappingTable.id inList stringIdenter and (identMappingAlias[historisk] eq false) }
            .map {
                val fraId = Ident.of(it[IdentMappingTable.id].value)
                val id = it[identMappingAlias[IdentMappingTable.id]]
                val historisk = it[identMappingAlias[historisk]]
                val internIdent = it[identMappingAlias[internIdent]]
                val identType = it[identMappingAlias[identType]]
                val ident = id.value.tilIdentType(identType)
                IdentMapping(fraId, IdentInfo(ident, historisk, internIdent))
            }
            .groupBy { it.fraIdent }
            .mapValues { (key, value) -> value
                .map { it.identInfo }
                .filter { !it.historisk }
                .map { it.ident }
                .finnForetrukketIdent()
            }
    }

    private fun String.tilIdentType(identType: String): Ident {
        return when (val identType = identType) {
            "FNR" -> Fnr(this)
            "NPID" -> Npid(this)
            "DNR" -> Dnr(this)
            "AKTOR_ID" -> AktorId(this)
            else -> throw IllegalArgumentException("Ukjent identType: $identType")
                .also { log.error(it.message, it) }
        }
    }

}

fun IdentInformasjon.toIdentType() = Ident.of(this.ident).toIdentType()
fun Ident.toIdentType(): String {
    return when (this) {
        is AktorId -> "AKTOR_ID"
        is Dnr -> "DNR"
        is Fnr -> "FNR"
        is Npid -> "NPID"
    }
}

data class IdentMapping(
    val fraIdent: Ident,
    val identInfo: IdentInfo,
)

data class IdentInfo(
    val ident: Ident,
    val historisk: Boolean,
    val internIdent: Long
)

sealed class IdentEndring(val ident: Ident, val historisk: Boolean, val internIdent: Long)
class NyIdent(ident: Ident, historisk: Boolean, internIdent: Long): IdentEndring(ident, historisk, internIdent)
class BleHistorisk(ident: Ident, internIdent: Long): IdentEndring(ident, true, internIdent)
class BleSlettet(ident: Ident, historisk: Boolean, internIdent: Long): IdentEndring(ident, historisk, internIdent)
class IngenEndring(ident: Ident, historisk: Boolean, internIdent: Long): IdentEndring(ident, historisk, internIdent)

fun List<IdentInfo>.finnEndringer(oppdaterteIdenter: List<OppdatertIdent>): List<IdentEndring> {
    val internIdent = this.map { it.internIdent }.distinct()
        .also { require(it.size == 1) { "Fant ${it.size} forskjellige intern-identer ved oppdatering av identer-endringer" } }
        .first()

    val endringerPåEksiterendeIdenter = this.map { eksisterendeIdent ->
        val identMatch = oppdaterteIdenter.find { eksisterendeIdent.ident == it.ident }
        when {
            identMatch == null -> BleSlettet(eksisterendeIdent.ident, eksisterendeIdent.historisk, internIdent)
            !eksisterendeIdent.historisk && identMatch.historisk -> BleHistorisk(eksisterendeIdent.ident, internIdent)
            else -> IngenEndring(eksisterendeIdent.ident, identMatch.historisk, internIdent)
        }
    }

    val innkommendeIdenter = oppdaterteIdenter.toSet().map { IdentInfo(it.ident, it.historisk, internIdent) }
    val nyeIdenter = (innkommendeIdenter - this.toSet()).map { NyIdent(it.ident, it.historisk, internIdent) }
    return endringerPåEksiterendeIdenter + nyeIdenter
}