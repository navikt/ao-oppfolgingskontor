package no.nav.services

import no.nav.db.Fnr
import no.nav.domain.KontorId
import no.nav.domain.KontorTilordning
import no.nav.domain.events.AOKontorEndret
import no.nav.domain.events.OppfolgingsPeriodeStartetLokalKontorTilordning
import no.nav.domain.events.OppfolgingsperiodeStartetNoeTilordning
import no.nav.http.client.AlderFunnet
import no.nav.http.client.AlderResult
import no.nav.http.client.GTKontorFunnet
import no.nav.http.client.GTKontorResultat

class AutomatiskKontorRutingService(
    private val gtKontorProvider: suspend (fnr: String) -> GTKontorResultat,
    private val aldersProvider: suspend (fnr: String) -> AlderResult,
    private val fnrProvider: suspend (aktorId: String) -> Fnr?,
) {
    suspend fun tilordneKontorAutomatisk(aktorId: String) {
        val fnr = fnrProvider(aktorId)
            ?: throw IllegalArgumentException("Fant ikke fnr for aktorId: $aktorId")
        val gtKontorResultat = gtKontorProvider(fnr)
        val aldersResultat = aldersProvider(fnr)
        val kontorTilordning = hentTilordning(
            fnr,
            if (gtKontorResultat is GTKontorFunnet) gtKontorResultat.kontorId else null,
            if (aldersResultat is AlderFunnet) aldersResultat.alder else null,
            hentProfilering(fnr))
        KontorTilordningService.tilordneKontor(kontorTilordning)
    }

    private fun hentProfilering(fnr: String) = "Bra"

    private fun hentTilordning(
        fnr: String,
        gtKontor: KontorId?,
        alder: Int?,
        profilering: String?
    ): AOKontorEndret {
        if (alder == null) throw IllegalArgumentException("Alder == null")
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