package no.nav.services

import no.nav.domain.KontorId
import no.nav.domain.KontorTilordning
import no.nav.domain.events.AOKontorEndret
import no.nav.domain.events.OppfolgingsPeriodeStartetLokalKontorTilordning
import no.nav.domain.events.OppfolgingsperiodeStartetNoeTilordning
import no.nav.http.client.GTKontorFunnet
import no.nav.http.client.GTKontorResultat

class AutomatiskKontorRutingService(
    private val gtKontorProvider: suspend (fnr: String) -> GTKontorResultat,
) {
    suspend fun tilordneKontorAutomatisk(aktorId: String) {
        val fnr = hentFnrFraPDL(aktorId)
        val gtKontorResultat = gtKontorProvider(fnr)
        val kontorTilordning = hentTilordning(
            fnr,
            if (gtKontorResultat is GTKontorFunnet) gtKontorResultat.kontorId else null,
            hentAlder(fnr),
            hentProfilering(fnr))
        KontorTilordningService.tilordneKontor(kontorTilordning)
    }

    private fun hentFnrFraPDL(fnr: String) = "12345678901"
    private fun hentProfilering(fnr: String) = "Bra"
    private fun hentAlder(fnr: String) = 35

    private fun hentTilordning(
        fnr: String,
        gtKontor: KontorId?,
        alder: Int,
        profilering: String?
    ): AOKontorEndret {
        return when {
            profilering == "Bra" && alder > 30 -> {
                OppfolgingsperiodeStartetNoeTilordning(fnr)
            }

            gtKontor == null -> {
                OppfolgingsPeriodeStartetLokalKontorTilordning(
                    KontorTilordning(fnr, KontorId("2990"))
                )
            }

            else -> {
                OppfolgingsPeriodeStartetLokalKontorTilordning(
                    KontorTilordning(fnr, gtKontor)
                )
            }
        }
    }

}