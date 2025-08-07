package services

import db.table.IdentMappingTable
import db.table.IdentMappingTable.historisk
import db.table.IdentMappingTable.identType
import db.table.IdentMappingTable.internIdent
import db.table.IdentMappingTable.updatedAt
import db.table.InternIdentSequence
import db.table.nextValueOf
import no.nav.db.*
import no.nav.http.client.*
import no.nav.http.graphql.generated.client.enums.IdentGruppe
import no.nav.http.graphql.generated.client.hentfnrquery.IdentInformasjon
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.sql.BatchUpdateException
import java.time.ZonedDateTime

class IdentService(
    val identForAktorIdProvider: suspend (aktorId: String) -> IdenterResult,
) {
    private val log = LoggerFactory.getLogger(IdentService::class.java)

    suspend fun hentForetrukketIdentFor(ident: Ident): IdentResult {
        val lokaltLagretIdent = hentLokalIdent(ident)
        if (lokaltLagretIdent != null) return lokaltLagretIdent
        return identForAktorIdProvider(ident.value)
            .also {
                if (it is IdenterFunnet) {
                    lagreNyIdentMapping(it)
                }
            }.finnForetrukketIdent()
    }

    private fun hentLokalIdent(ident: Ident): IdentFunnet? {
        try {
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
                    return IdentFunnet(foretrukketIdent!!)
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
                val internId = nextValueOf(InternIdentSequence)
                IdentMappingTable.batchInsert(identer.identer) {
                    this[IdentMappingTable.id] = it.ident
                    this[identType] = it.toIdentType()
                    this[internIdent] = internId
                    this[historisk] = it.historisk
                    this[updatedAt] = ZonedDateTime.now().toOffsetDateTime()
                }
            }
        } catch (e: ExposedSQLException) {
            val batchUpdateException = e.cause as? BatchUpdateException

            val regex = Regex("Key \\(ident\\)=\\((\\d+)\\) already exists")
            if (batchUpdateException?.message == null) return
            val match = regex.find(batchUpdateException.message!!)
            if (match == null) {
                log.error("Ingen matchende ident", e)
                return
            }

            val lokaleIdenter = hentIdentMappinger(Ident.of(match.groupValues[1]))

            log.error(
                "Identer inn: ${
                    identer.identer.joinToString(",")
                }, Lokale identer: ${lokaleIdenter.joinToString(",")}"
            )

        } catch (e: Throwable) {
            log.error("Kunne ikke lagre ident-mapping ${e.message}", e)
        }
    }

    private fun oppdaterIdentMapping(identer: IdenterFunnet) {
        try {
            transaction {
                val aktorId = identer.identer.first { it.gruppe == IdentGruppe.AKTORID }.ident
                val internIdent = IdentMappingTable
                    .select(internIdent)
                    .where { IdentMappingTable.id eq aktorId }
                    .map { row -> row[internIdent] }
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

    private fun IdentInformasjon.toIdentType(): String {
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
            .where { (IdentMappingTable.id eq identInput.value) and (identMappingAlias[historisk] eq false) }
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
