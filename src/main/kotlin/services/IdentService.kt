package services

import db.table.IdentMappingTable
import db.table.IdentMappingTable.historisk
import db.table.IdentMappingTable.identType
import db.table.IdentMappingTable.internIdent
import db.table.IdentMappingTable.updatedAt
import db.table.InternIdentSequence
import db.table.nextValueOf
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
import no.nav.http.graphql.generated.client.enums.IdentGruppe
import no.nav.http.graphql.generated.client.hentfnrquery.IdentInformasjon
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchUpsert
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.lang.IllegalArgumentException
import java.time.ZonedDateTime

class IdentService(
    val alleIdenterProvider: suspend (aktorId: String) -> IdenterResult,
) {
    private val log = LoggerFactory.getLogger(IdentService::class.java)

    suspend fun hentForetrukketIdentFor(ident: Ident): IdentResult {
        val lokaltLagretIdent = hentLokalIdent(ident)
        if (lokaltLagretIdent != null) return lokaltLagretIdent
        return alleIdenterProvider(ident.value)
            .let { indenterResult ->
                if (indenterResult is IdenterFunnet) {
                    runCatching { oppdaterAlleIdentMappinger(indenterResult) }
                        .fold(
                            { it },
                            { IdentOppslagFeil("asdsa") }
                        )
                }
                indenterResult.finnForetrukketIdent()
            }
    }

    private fun hentLokalIdent(ident: Ident): IdentFunnet? {
        try {
            val identMappings = hentIdentMappinger(ident)
            when {
                identMappings.isNotEmpty() -> {
                    val fnr = identMappings.firstOrNull { it is Fnr }
                    return IdentFunnet(fnr ?: identMappings.first { it is Npid })
                }
                else -> return null
            }
        } catch (e: Exception) {
            log.error("Feil ved oppslag på lokal-ident", e)
            return null
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
            log.error("Kunne ikke lagre ident-mapping ${e.message}", e)
        }
    }

    private fun oppdaterIdentMapping(identer: IdenterFunnet) {
        try {
            transaction {
                val aktorId = identer.identer.first { it.gruppe == IdentGruppe.AKTORID }.ident
                val internIdent = IdentMappingTable
                    .select(IdentMappingTable.internIdent)
                    .where { IdentMappingTable.id eq aktorId }
                    .map { row -> row[IdentMappingTable.internIdent] }
                    .first()
                IdentMappingTable.batchUpsert(identer.identer) {
                    this[IdentMappingTable.id] = it.ident
                    this[IdentMappingTable.internIdent] = internIdent
                    this[identType] = it.toIdentType()
                    this[historisk] = it.historisk
                    this[updatedAt] = ZonedDateTime.now().toOffsetDateTime()
                }
            }
        } catch (e: Throwable) {
            log.error("Kunne ikke lagre ident-mapping ${e.message}", e)
        }
    }

    private fun IdentInformasjon.toIdentType() : String {
        val ident = Ident.of(this.ident)
        return when (ident) {
            is AktorId -> "AKTOR_ID"
            is Dnr -> "DNR"
            is Fnr -> "FNR"
            is Npid -> "NPID"
        }
    }

    private fun hentIdentMappinger(identInput: Ident): List<Ident> = transaction {
        val identMappingAlias = IdentMappingTable.alias("ident_mapping_alias")

        IdentMappingTable.join(
            identMappingAlias,
            JoinType.INNER,
            onColumn = internIdent,
            otherColumn = identMappingAlias[internIdent]
        )
            .select(identMappingAlias[IdentMappingTable.id], identMappingAlias[identType], identMappingAlias[historisk])
            .where { (IdentMappingTable.id eq identInput.value) and (historisk eq false) }
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
