package no.nav.services

import no.nav.db.table.ArenaKontorTable.sistEndretDatoArena
import no.nav.db.table.KontorNavnTable
import no.nav.domain.KontorId
import no.nav.domain.KontorNavn
import no.nav.http.client.Norg2Client
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.batchUpsert
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.OffsetDateTime

class KontorNavnService(
    val norg2Client: Norg2Client
) {
    suspend fun getKontorNavn(kontorId: KontorId): KontorNavn {
        return hentLagretKontorNavn(kontorId)?.kontorNavn ?:
            norg2Client.hentKontor(kontorId).let { KontorNavn(it.navn) }
                .also { kontorNavn -> lagreKontorNavn(kontorId, kontorNavn) }
    }

    suspend fun friskOppAlleKontorNavn() {
        val kontorer = norg2Client.hentAlleEnheter()
        transaction {
            KontorNavnTable.batchUpsert(kontorer) { kontoret ->
                this[KontorNavnTable.kontorId] = kontoret.kontorId
                this[KontorNavnTable.kontorNavn] = kontoret.navn
                this[sistEndretDatoArena] = OffsetDateTime.now()
            }
        }
    }

    private fun lagreKontorNavn(kontorId: KontorId, kontorNavn: KontorNavn) {
        transaction {
            KontorNavnTable.insert {
                it[id] = kontorId.id
                it[KontorNavnTable.kontorNavn] = kontorNavn.navn
                it[sistEndretDatoArena] = OffsetDateTime.now()
            }
        }
    }

    private fun hentLagretKontorNavn(kontorId: KontorId): KontorMedNavn? {
        return transaction {
            KontorNavnTable
                .select(KontorNavnTable.kontorNavn, KontorNavnTable.kontorId)
                .where { KontorNavnTable.kontorId eq kontorId.id }
                .map { row ->
                    KontorMedNavn(
                        KontorNavn(row[KontorNavnTable.kontorNavn]),
                        KontorId(row[KontorNavnTable.kontorId]),
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

