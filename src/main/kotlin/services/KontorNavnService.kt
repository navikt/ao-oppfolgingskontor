package no.nav.services

import no.nav.db.table.ArenaKontorTable
import no.nav.db.table.ArenaKontorTable.id
import no.nav.db.table.ArenaKontorTable.kontorId
import no.nav.db.table.ArenaKontorTable.sistEndretDatoArena
import no.nav.db.table.KontorNavnTable
import no.nav.domain.KontorId
import no.nav.domain.KontorNavn
import no.nav.http.client.Norg2Client
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.OffsetDateTime

class KontorNavnService(
    val norg2Client: Norg2Client
) {
    // Skal på et senere tidspunkt cache alle navn i en tabell men foreløpig bare henter vi den fra Norg løpende
    suspend fun getKontorNavn(kontorId: KontorId): KontorNavn {
        return KontorNavn(norg2Client.hentKontor(kontorId).navn)
    }

    private fun lagreKontorNavn(kontorId: KontorId, kontorNavn: KontorNavn) {
        transaction {
            KontorNavnTable.insert {
                it[id] = kontorId.id
                it[KontorNavnTable.na] = kontorNavn.navn
                it[sistEndretDatoArena] = OffsetDateTime.now()
            }
        }
    }

    private fun hentLagretKontorNavn() {}
}