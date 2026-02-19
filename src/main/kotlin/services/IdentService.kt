package services

import db.table.IdentMappingTable
import db.table.IdentMappingTable.historisk
import db.table.IdentMappingTable.identType
import db.table.IdentMappingTable.internIdent
import db.table.IdentMappingTable.slettetHosOss
import db.table.IdentMappingTable.updatedAt
import db.table.InternIdentSequence
import db.table.nextValueOf
import domain.IdenterFunnet
import domain.IdenterIkkeFunnet
import domain.IdenterOppslagFeil
import domain.IdenterResult
import kafka.consumers.OppdatertIdent
import no.nav.db.AktorId
import no.nav.db.Dnr
import no.nav.db.Fnr
import no.nav.db.Ident
import no.nav.db.IdentSomKanLagres
import no.nav.db.InternIdent
import no.nav.db.Npid
import no.nav.db.finnForetrukketIdent
import no.nav.http.client.IdentFunnet
import no.nav.http.client.IdentIkkeFunnet
import no.nav.http.client.IdentOppslagFeil
import no.nav.http.client.IdentResult
import no.nav.http.client.PdlIdenterFunnet
import no.nav.http.client.PdlIdenterIkkeFunnet
import no.nav.http.client.PdlIdenterOppslagFeil
import no.nav.http.client.PdlIdenterResult
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.batchUpsert
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
import java.time.ZonedDateTime

class IdentService(
    val hentAlleIdenterSynkrontFraPdl: suspend (aktorId: String) -> PdlIdenterResult,
) {
    private val log = LoggerFactory.getLogger(IdentService::class.java)

    suspend fun veksleAktorIdIForetrukketIdent(ident: AktorId): IdentResult {
        val lokalIdentResult = hentLokalIdent(ident)
        val lokaltLagretIdent = when {
            lokalIdentResult.isSuccess -> lokalIdentResult.getOrNull()
            else -> {
                val error = lokalIdentResult.exceptionOrNull()
                log.warn("Feil ved oppslag på lokalt lagret ident: ${error?.message}", error)
                hentAlleIdenterOgOppdaterMapping(ident)
                    .finnForetrukketIdent()
            }
        }
        if (lokaltLagretIdent != null) return lokaltLagretIdent
        return hentAlleIdenterOgOppdaterMapping(ident)
            .finnForetrukketIdent()
    }

    /* Tenkt kalt ved endring på aktor-v2 topic (endring i identer) */
    suspend fun håndterEndringPåIdenter(ident: Ident): IdenterResult = hentAlleIdenterOgOppdaterMapping(ident)

    fun håndterEndringPåIdenter(aktorId: AktorId, oppdaterteIdenterFraPdl: List<OppdatertIdent>): Int {
        val lagredeIdenter = hentIdentMappinger(
            oppdaterteIdenterFraPdl.map { it.ident } + listOf(aktorId),
            includeHistorisk = true
        )

        val identKanIgnoreres = lagredeIdenter.isEmpty()
        if (identKanIgnoreres) return 0

        val endringer = finnEndringer(lagredeIdenter, oppdaterteIdenterFraPdl)
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
                        .finnForetrukketIdent() ?: throw Exception("Fant ingen 'ikke-aktorid'-identer i databasen")
                    return Result.success(IdentFunnet(foretrukketIdent))
                }

                else -> return Result.success(null)
            }
        }
    }

    private suspend fun hentAlleIdenterOgOppdaterMapping(ident: Ident): IdenterResult {
        return when (val identer = hentAlleIdenterSynkrontFraPdl(ident.value)) {
            is PdlIdenterFunnet -> {
                val internIdent = oppdaterAlleIdentMappinger(identer.identer)
                IdenterFunnet(
                    identer.identer,
                    identer.inputIdent,
                    internIdent
                )
            }
            is PdlIdenterIkkeFunnet -> IdenterIkkeFunnet(identer.message)
            is PdlIdenterOppslagFeil -> IdenterOppslagFeil(identer.message)
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
            .where { IdentMappingTable.id inList (identer.map { it.value }) }
            .map {
                IdentInfo(
                    Ident.validateOrThrow(it[IdentMappingTable.id].value, it[historisk].toKnownHistoriskStatus()),
                    it[historisk],
                    it[internIdent]
                )
            }
    }

    private fun oppdaterAlleIdentMappinger(identer: List<Ident>): InternIdent {
        try {
            val eksitrerendeInternIder = hentEksisterendeIdenter(identer)
                .map { it.internIdent }

            return transaction {
                val internId = getOrCreateInternId(eksitrerendeInternIder)
                IdentMappingTable.batchUpsert(identer) {
                    this[IdentMappingTable.id] = it.value
                    this[identType] = it.toIdentType()
                    this[internIdent] = internId
                    this[historisk] = when (it.historisk) {
                        Ident.HistoriskStatus.HISTORISK -> true
                        Ident.HistoriskStatus.AKTIV -> false
                        Ident.HistoriskStatus.UKJENT -> throw IllegalStateException("Kan ikke lagre identer med ukjent historisk status")
                    }
                    this[updatedAt] = ZonedDateTime.now().toOffsetDateTime()
                }
                InternIdent(internId)
            }
        } catch (e: Throwable) {
            throw Exception("Kunne ikke lagre ident-mapping ${e.message}", e)
        }
    }

    /**
     * Henter alle tilhørende identer på en bruker, inkl historiske
     */
    public suspend fun hentAlleIdenter(identInput: Ident): IdenterResult {
        try {
            val fullIdentInfo = hentIdentMappinger(identInput, true)
            val internIdent = fullIdentInfo.map { it.internIdent }.toSet().single().let { InternIdent(it) }
            val alleIdenter = fullIdentInfo.map { it.ident }
            return when (alleIdenter.size) {
                // Fallback til å hente synkront fra PDL
                0 -> hentAlleIdenterOgOppdaterMapping(identInput)
                else -> IdenterFunnet(
                    identer = alleIdenter,
                    inputIdent = identInput,
                    internIdent = internIdent
                )
            }
        } catch (e: Throwable) {
            log.error("Feil ved henting av alle identer for input-ident", e)
            return IdenterOppslagFeil("Feil ved henting av alle identer for input-ident: ${e.message}")
        }
    }

    suspend fun hentAktorId(ident: IdentSomKanLagres): AktorId? {
        return transaction {
            val identMappingAlias = IdentMappingTable.alias("ident_mapping_alias")

            IdentMappingTable.join(
                identMappingAlias,
                JoinType.INNER,
                onColumn = internIdent,
                otherColumn = identMappingAlias[internIdent]
            )
                .select(
                    identMappingAlias[IdentMappingTable.id],
                    identMappingAlias[identType],
                    identMappingAlias[historisk],
                    identMappingAlias[internIdent]
                )
                .where { (IdentMappingTable.id eq ident.value) }
                .andWhere { identMappingAlias[historisk] eq false }
                .andWhere { identMappingAlias[identType] eq "AKTOR_ID" }
                .map {
                    val id = it[identMappingAlias[IdentMappingTable.id]]
                    val historisk = it[identMappingAlias[historisk]]
                    val historiskStatus = historisk.toKnownHistoriskStatus()
                    AktorId(id.value, historiskStatus)
                }
                .firstOrNull()
        }
    }

    /**
     * Henter alle koblede identer utenom historiske
     */
    private fun hentIdentMappinger(identInput: Ident): List<Ident> =
        hentIdentMappinger(identInput, false).map { it.ident }

    fun hentIdentMappinger(identInput: Ident, includeHistorisk: Boolean): List<IdentInfo> =
        hentIdentMappinger(listOf(identInput), includeHistorisk)

    private fun hentIdentMappinger(identeneTilEnPerson: List<Ident>, includeHistorisk: Boolean): List<IdentInfo> =
        transaction {
            val identMappingAlias = IdentMappingTable.alias("ident_mapping_alias")

            IdentMappingTable.join(
                identMappingAlias,
                JoinType.INNER,
                onColumn = internIdent,
                otherColumn = identMappingAlias[internIdent]
            )
                .select(
                    identMappingAlias[IdentMappingTable.id],
                    identMappingAlias[identType],
                    identMappingAlias[historisk],
                    identMappingAlias[internIdent]
                )
                .where { (IdentMappingTable.id inList identeneTilEnPerson.map { it.value }) }
                .let { query ->
                    if (!includeHistorisk) {
                        query.andWhere { identMappingAlias[historisk] eq false }
                    } else query
                }
                .map {
                    val id = it[identMappingAlias[IdentMappingTable.id]]
                    val historisk = it[identMappingAlias[historisk]]
                    val historiskStatus = historisk.toKnownHistoriskStatus()
                    val internIdent = it[identMappingAlias[internIdent]]
                    val ident = when (val identType = it[identMappingAlias[identType]]) {
                        "FNR" -> Fnr(id.value, historiskStatus)
                        "NPID" -> Npid(id.value, historiskStatus)
                        "DNR" -> Dnr(id.value, historiskStatus)
                        "AKTOR_ID" -> AktorId(id.value, historiskStatus)
                        else -> throw IllegalArgumentException("Ukjent identType: $identType")
                            .also { log.error(it.message, it) }
                    }
                    IdentInfo(ident, historisk, internIdent)
                }
                .also { identInfo ->
                    val internIdenter = identInfo.map { it.internIdent }.distinct()
                    if (internIdenter.size > 1) {
                        log.error("Fant flere intern-ident-er på hentIdentMappinger, er input-idenene bare 1 person? Hvis ikke er indenter kanskje lagret feil")
                    }
                }
        }

    fun finnEndringer(lagredeIdenter: List<IdentInfo>, oppdaterteIdenter: List<OppdatertIdent>): List<IdentEndring> {
        val internIdent = velgInternIdent(lagredeIdenter)

        val endringerPåEksisterendeIdenter = lagredeIdenter.map { eksisterendeIdent ->
            val identMatch = oppdaterteIdenter.find { eksisterendeIdent.ident == it.ident }
            when {
                identMatch == null -> BleSlettet(eksisterendeIdent.ident, eksisterendeIdent.historisk, internIdent)
                !eksisterendeIdent.historisk && identMatch.historisk -> BleHistorisk(
                    eksisterendeIdent.ident,
                    internIdent
                )

                else -> IngenEndring(eksisterendeIdent.ident, identMatch.historisk, internIdent)
            }
        }

        val innkommendeIdenter = oppdaterteIdenter.toSet().map { IdentInfo(it.ident, it.historisk, internIdent) }
        val nyeIdenter =
            (innkommendeIdenter - lagredeIdenter.toSet()).map { NyIdent(it.ident, it.historisk, internIdent) }
        return endringerPåEksisterendeIdenter + nyeIdenter
    }

    private fun velgInternIdent(lagredeIdenter: List<IdentInfo>): Long {
        val antallInternIdenter = lagredeIdenter.map { it.internIdent }.distinct().size
        return if (antallInternIdenter == 1) {
            lagredeIdenter.first().internIdent
        } else {
            log.info("Håndterer merge - velger internIdent")
            val valgtInternIdentVedMerge = lagredeIdenter.minBy { it.internIdent }.internIdent
            return valgtInternIdentVedMerge
        }
    }
}

fun Boolean.toKnownHistoriskStatus(): Ident.HistoriskStatus {
    return if (this) Ident.HistoriskStatus.HISTORISK else Ident.HistoriskStatus.AKTIV // Hvis historisk er den boolske verdien "this" true
}

fun Ident.toIdentType(): String {
    return when (this) {
        is AktorId -> "AKTOR_ID"
        is Dnr -> "DNR"
        is Fnr -> "FNR"
        is Npid -> "NPID"
    }
}

data class IdentInfo(
    val ident: Ident,
    val historisk: Boolean,
    val internIdent: Long
)

sealed class IdentEndring(val ident: Ident, val historisk: Boolean, val internIdent: Long)
class NyIdent(ident: Ident, historisk: Boolean, internIdent: Long) : IdentEndring(ident, historisk, internIdent)
class BleHistorisk(ident: Ident, internIdent: Long) : IdentEndring(ident, true, internIdent)
class BleSlettet(ident: Ident, historisk: Boolean, internIdent: Long) : IdentEndring(ident, historisk, internIdent)
class IngenEndring(ident: Ident, historisk: Boolean, internIdent: Long) : IdentEndring(ident, historisk, internIdent)

fun IdenterResult.finnForetrukketIdent(): IdentResult {
    return when (this) {
        is IdenterFunnet -> this.identer.finnForetrukketIdent()?.let { IdentFunnet(it) }
            ?: IdentIkkeFunnet("Fant ingen foretrukket ident")

        is IdenterIkkeFunnet -> IdentIkkeFunnet(this.message)
        is IdenterOppslagFeil -> IdentOppslagFeil(this.message)
    }
}

fun PdlIdenterResult.finnForetrukketIdent(): IdentResult {
    return when (this) {
        is PdlIdenterFunnet -> this.identer.finnForetrukketIdent()?.let { IdentFunnet(it) }
            ?: IdentIkkeFunnet("Fant ingen foretrukket ident")

        is PdlIdenterIkkeFunnet -> IdentIkkeFunnet(this.message)
        is PdlIdenterOppslagFeil -> IdentOppslagFeil(this.message)
    }
}