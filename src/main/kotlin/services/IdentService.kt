package services

import db.table.IdentMappingTable
import db.table.IdentMappingTable.identType
import no.nav.db.AktorId
import no.nav.db.Dnr
import no.nav.db.Fnr
import no.nav.db.Ident
import no.nav.db.Npid
import no.nav.http.client.IdentFunnet
import no.nav.http.client.IdentResult
import no.nav.http.client.IdenterFunnet
import no.nav.http.client.IdenterResult
import no.nav.http.client.finnIdent
import no.nav.http.graphql.generated.client.enums.IdentGruppe
import no.nav.http.graphql.generated.client.hentfnrquery.IdentInformasjon
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.batchUpsert
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.lang.IllegalArgumentException
import java.time.ZonedDateTime

class IdentService(
    val identForAktorIdProvider: suspend (aktorId: String) -> IdenterResult,
) {
    private val log = LoggerFactory.getLogger(IdentService::class.java)

    suspend fun hentIdentFraAktorId(aktorId: String): IdentResult {
        val lokaltLagretIdent = hentLokalIdent(aktorId)
        if (lokaltLagretIdent != null) return lokaltLagretIdent
        return identForAktorIdProvider(aktorId)
            .also {
                if (it is IdenterFunnet) {
                    lagreNyIdentMapping(it)
                }
            }.finnIdent()
    }

    private fun hentLokalIdent(aktorId: String): IdentFunnet? {
        try {
            val identMappings = hentIdentMappinger(aktorId)
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

    private fun lagreNyIdentMapping(identer: IdenterFunnet) {
        try {
            transaction {
                IdentMappingTable.batchInsert(identer.identer) {
                    this[IdentMappingTable.id] = it.ident
                    this[IdentMappingTable.identType] = it.toIdentType()
                    this[IdentMappingTable.historisk] = it.historisk
                    this[IdentMappingTable.updatedAt] = ZonedDateTime.now().toOffsetDateTime()

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
                val internIdent = IdentMappingTable.select(IdentMappingTable.internIdent)
                    .where { IdentMappingTable.id eq aktorId }
                    .map { row ->
                        row[IdentMappingTable.internIdent]
                    }.first()
                IdentMappingTable.batchUpsert(identer.identer) {
                    this[IdentMappingTable.id] = it.ident
                    this[IdentMappingTable.internIdent] = internIdent
                    this[IdentMappingTable.identType] = it.toIdentType()
                    this[IdentMappingTable.historisk] = it.historisk
                    this[IdentMappingTable.updatedAt] = ZonedDateTime.now().toOffsetDateTime()

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

    private fun hentIdentMappinger(aktorIdInput: String): List<Ident> = transaction {

        val identMappingAlias = IdentMappingTable.alias("ident_mapping_alias")

        IdentMappingTable.join(
            identMappingAlias,
            JoinType.INNER,
            onColumn = IdentMappingTable.internIdent,
            otherColumn = identMappingAlias[IdentMappingTable.internIdent]
        )
            .select(IdentMappingTable.id, identType, IdentMappingTable.historisk)
            .where { (IdentMappingTable.id eq aktorIdInput) and (IdentMappingTable.historisk eq false) }
            .map {
                val id = it[IdentMappingTable.id]
                val identType = it[identType]
                when (identType) {
                    "FNR" -> Fnr(id.value)
                    "NPID" -> Npid(id.value)
                    "DNR" -> Dnr(id.value)
                    "AKTOR_ID" -> AktorId(id.value)
                    else -> throw IllegalArgumentException("Ukjent identType: $identType").also {
                            log.error(it.message, it)
                        }
                }
            }
    }
}
