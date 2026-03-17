package services

import no.nav.NavAnsatt
import no.nav.db.Ident
import no.nav.db.IdentSomKanLagres
import no.nav.db.table.ArbeidsOppfolgingKontorTable
import no.nav.domain.KontorId
import no.nav.domain.KontorTilordning
import no.nav.domain.OppfolgingsperiodeId
import no.nav.domain.Veileder
import no.nav.domain.events.KontorEndretPgaAdminKontorSammenslåing
import no.nav.services.TilordneKontor
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

class `KontorSammenslåingService`(
    val tilordneKontor: TilordneKontor
) {

    fun slåSammenKontorer(navAnsatt: NavAnsatt, kontorer: KontorSammenSlåing) {
        // Sikre at alle endringer skjer samtidig
        transaction {
            prosesserAlleBrukereIBatch(navAnsatt, kontorer)
        }
    }

    private tailrec fun prosesserAlleBrukereIBatch(navAnsatt: NavAnsatt, kontorer: KontorSammenSlåing) {
        val batchMedBrukereSomHvorKontorSkalMigreres = hentKontorBatch(kontorer.fraKontorer, 2000)
        if (batchMedBrukereSomHvorKontorSkalMigreres.isEmpty()) return
        // For hver entry, sett kontoret
        // Alle kontor-endringer må ha en tilsvarende kontor-historikk entry i samme transaksjon som selve endringen i kontoret
        batchMedBrukereSomHvorKontorSkalMigreres
            .map { tilordneKontorForBruker(Veileder(navAnsatt.navIdent), it, kontorer.tilKontor) }

        prosesserAlleBrukereIBatch(navAnsatt, kontorer)
    }

    fun antallKontorerSomSkalEndres(fraKontorer: List<KontorId>): Long {
        return ArbeidsOppfolgingKontorTable
            .selectAll()
            .where { ArbeidsOppfolgingKontorTable.kontorId inList fraKontorer.map { it.id } }
            .count()
    }

    private fun hentKontorBatch(fraKontorer: List<KontorId>, batchSize: Int): List<Pair<IdentSomKanLagres, OppfolgingsperiodeId>> {
        return ArbeidsOppfolgingKontorTable
            .select(ArbeidsOppfolgingKontorTable.oppfolgingsperiodeId, ArbeidsOppfolgingKontorTable.id)
            .where { ArbeidsOppfolgingKontorTable.kontorId inList fraKontorer.map { it.id } }
            .limit(batchSize)
            .mapNotNull {
                val ident = Ident.validateOrThrow(it[ArbeidsOppfolgingKontorTable.id].value, Ident.HistoriskStatus.UKJENT)
                val oppfolgingsperiode = OppfolgingsperiodeId(it[ArbeidsOppfolgingKontorTable.oppfolgingsperiodeId])
                if (ident !is IdentSomKanLagres) return@mapNotNull null
                ident to oppfolgingsperiode
            }
    }

    private fun tilordneKontorForBruker(veileder: Veileder, bruker: Pair<IdentSomKanLagres, OppfolgingsperiodeId>, kontorId: KontorId) {
        tilordneKontor(KontorEndretPgaAdminKontorSammenslåing(
            tilordning = KontorTilordning(
                bruker.first,
                kontorId,
                bruker.second,
            ),
            adminBruker = veileder
        ),false)
    }
}

data class KontorSammenSlåing(
    val fraKontorer: List<KontorId>,
    val tilKontor: KontorId
)

