package no.nav.services

import no.nav.db.table.KontorNavnTable
import no.nav.domain.KontorId
import no.nav.domain.KontorNavn
import no.nav.http.client.Norg2Client
import org.jetbrains.exposed.sql.batchUpsert
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime

class KontorNavnService(
    val norg2Client: Norg2Client
) {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    suspend fun getKontorNavn(kontorId: KontorId): KontorNavn {
        return hentLagretKontorNavn(kontorId)?.kontorNavn ?:
            norg2Client.hentKontor(kontorId).let { KontorNavn(it.navn) }
                .also { kontorNavn -> lagreKontorNavn(kontorId, kontorNavn) }
    }

    suspend fun friskOppAlleKontorNavn() {
        logger.info("Henter alle kontornavn fra Norg2")
        val kontorer = norg2Client.hentAlleEnheter()
        transaction {
            KontorNavnTable.batchUpsert(kontorer) { kontoret ->
                this[KontorNavnTable.id] = kontoret.kontorId
                this[KontorNavnTable.kontorNavn] = kontoret.navn
                this[KontorNavnTable.updatedAt] = OffsetDateTime.now()
            }
        }
        logger.info("Ferdig med å friske opp alle kontornavn")
    }

    private fun lagreKontorNavn(kontorId: KontorId, kontorNavn: KontorNavn) {
        transaction {
            KontorNavnTable.insert {
                it[id] = kontorId.id
                it[KontorNavnTable.kontorNavn] = kontorNavn.navn
                it[KontorNavnTable.updatedAt] = OffsetDateTime.now()
            }
        }
    }

    private fun hentLagretKontorNavn(kontorId: KontorId): KontorMedNavn? {
        return transaction {
            KontorNavnTable
                .select(KontorNavnTable.kontorNavn, KontorNavnTable.id)
                .where { KontorNavnTable.id eq kontorId.id }
                .map { row ->
                    KontorMedNavn(
                        KontorNavn(row[KontorNavnTable.kontorNavn]),
                        KontorId(row[KontorNavnTable.id].value),
                    )
                }
                .firstOrNull()
        }
    }

    data class KontorMedNavn(
        val kontorNavn: KontorNavn,
        val kontorId: KontorId,
    )
}

