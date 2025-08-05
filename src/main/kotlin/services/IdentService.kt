package services

import db.table.IdentMappingTable
import db.table.IdentMappingTable.aktorId
import db.table.IdentMappingTable.fnr
import db.table.IdentMappingTable.npid
import no.nav.db.Fnr
import no.nav.db.Ident
import no.nav.db.Npid
import no.nav.http.client.IdentFunnet
import no.nav.http.client.IdentResult
import no.nav.http.client.IdenterFunnet
import no.nav.http.client.IdenterResult
import no.nav.http.client.finnIdent
import org.jetbrains.exposed.sql.batchUpsert
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

class IdentService(
    val identForAktorIdProvider: suspend (aktorId: String) -> IdenterResult,
) {
    private val log = LoggerFactory.getLogger(IdentService::class.java)

    suspend fun hentIdentFraAktorId(aktorId: String): IdentResult {
        val lokaltLagretIdent = hentLokalIdent(aktorId)
        if (lokaltLagretIdent != null) return lokaltLagretIdent
        return identForAktorIdProvider(aktorId)
            .also {
                if (it is IdentFunnet) {
                    lagreIdentMapping(aktorId, it.ident)
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

    private fun lagreIdentMapping(identer: IdenterFunnet, aktorId: String) {
        try {
            transaction {
                IdentMappingTable.batchUpsert(identer.identer) {
                    it[IdentMappingTable.aktorId] = aktorId
                    if (identer is Npid) {
                        it[IdentMappingTable.npid] = ident.value
                    } else {
                        it[IdentMappingTable.fnr] = ident.value
                    }
                }
            }
        } catch (e: Throwable) {
            log.error("Kunne ikke lagre ident-mapping ${e.message}", e)
        }
    }

    private fun hentIdentMappinger(aktorIdInput: String) = transaction {


        IdentMappingTable.select(npid, fnr)
            .where { aktorId eq aktorIdInput }.mapNotNull {
                val fnr = it[fnr]
                val npid = it[npid]
                when {
                    fnr != null -> Fnr(fnr)
                    npid != null -> Npid(npid)
                    else -> null
                }
            }
    }
}
