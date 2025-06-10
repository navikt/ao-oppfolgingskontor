package no.nav.services

import no.nav.domain.KontorId
import no.nav.domain.KontorTilordning
import no.nav.domain.events.AOKontorEndret
import no.nav.domain.events.OppfolgingsPeriodeStartetLokalKontorTilordning
import no.nav.domain.events.OppfolgingsperiodeStartetNoeTilordning
import no.nav.http.client.PoaoTilgangKtorHttpClient

class AutomatiskKontorRutingService(
    private val client: PoaoTilgangKtorHttpClient,
) {
    fun tilordneKontorAutomatisk(aktorId: String) {
        val fnr = hentFnrFraPDL(aktorId)
        val kontorTilordning = hentTilordning(
            hentFnrFraPDL(fnr),
            hentGtKontor(fnr),
            hentAlder(fnr),
            hentProfilering(fnr))
        KontorTilordningService.tilordneKontor(kontorTilordning)
    }

    private fun hentGtKontor(fnr: String): String? {
        return client.hentTilgangsattributter(fnr)
            .map { it.kontor }
            .getOrDefault { null }
    }
    private fun hentFnrFraPDL(fnr: String) = "12345678901"
    private fun hentProfilering(fnr: String) = "Bra"
    private fun hentAlder(fnr: String) = 35

    private fun hentTilordning(
        fnr: String,
        gtKontor: String?,
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
                    KontorTilordning(fnr, KontorId(gtKontor))
                )
            }
        }
    }

}