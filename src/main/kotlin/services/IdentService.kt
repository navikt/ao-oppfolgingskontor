package services

import db.table.IdentMappingTable
import db.table.IdentMappingTable.aktorId
import db.table.IdentMappingTable.fnr
import db.table.IdentMappingTable.npid
import no.nav.db.Fnr
import no.nav.db.Ident
import no.nav.db.Npid
import no.nav.http.client.FnrFunnet
import no.nav.http.client.FnrResult
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

class IdentService(
    val fnrForAktorIdProvider: suspend (aktorId: String) -> FnrResult,
) {
    private val log = LoggerFactory.getLogger(IdentService::class.java)

    suspend fun hentFnrFraAktorId(aktorId: String): FnrResult {
        val lokaltLagretIdent = hentLokalIdent(aktorId)
        if (lokaltLagretIdent != null) return lokaltLagretIdent
        return fnrForAktorIdProvider(aktorId)
            .also {
                if (it is FnrFunnet) {
                    lagreIdentMapping(aktorId, it.ident)
                }
            }
    }

    private fun hentLokalIdent(aktorId: String): FnrFunnet? {
        try {
            val identMappings = hentIdentMappinger(aktorId)
            when {
                identMappings.isNotEmpty() -> {
                    val fnr = identMappings.firstOrNull { it is Fnr }
                    return FnrFunnet(fnr ?: identMappings.first { it is Npid })
                }
                else -> return null
            }
        } catch (e: Exception) {
            log.error("Feil ved oppslag på lokal-ident", e)
            return null
        }
    }

    private fun lagreIdentMapping(aktorId: String, ident: Ident) {
        try {
            transaction {
                IdentMappingTable.insert {
                    it[IdentMappingTable.aktorId] = aktorId
                    if (ident is Npid) {
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
