package no.nav.services

import no.nav.db.Fnr
import no.nav.domain.KontorId
import no.nav.domain.KontorTilordning
import no.nav.domain.events.AOKontorEndret
import no.nav.domain.events.OppfolgingsPeriodeStartetLokalKontorTilordning
import no.nav.domain.events.OppfolgingsperiodeStartetNoeTilordning
import no.nav.http.client.AlderFunnet
import no.nav.http.client.AlderResult
import no.nav.http.client.FnrFunnet
import no.nav.http.client.FnrResult
import no.nav.http.client.GTKontorFunnet
import no.nav.http.client.GTKontorResultat
import no.nav.http.client.arbeidssogerregisteret.ProfileringEnum

sealed class ProfileringsResultat
data class ProfileringFunnet(val profilering: ProfileringEnum) : ProfileringsResultat()
data class ProfileringIkkeFunnet(val melding: String) : ProfileringsResultat()
data class ProfileringsResultatFeil(val error: Throwable) : ProfileringsResultat()

class AutomatiskKontorRutingService(
    private val gtKontorProvider: suspend (fnr: String) -> GTKontorResultat,
    private val aldersProvider: suspend (fnr: String) -> AlderResult,
    private val fnrProvider: suspend (aktorId: String) -> FnrResult,
    private val profileringProvider: suspend (fnr: String) -> ProfileringsResultat,
) {
    suspend fun tilordneKontorAutomatisk(aktorId: String) {
        val fnrResult = fnrProvider(aktorId)
        if (fnrResult !is FnrFunnet) throw IllegalArgumentException("Fant ikke fnr for aktorId: $aktorId")
        val fnr = fnrResult.fnr
        val gtKontorResultat = gtKontorProvider(fnr)
        val aldersResultat = aldersProvider(fnr)
        val kontorTilordning = hentTilordning(
            fnr,
            if (gtKontorResultat is GTKontorFunnet) gtKontorResultat.kontorId else null,
            if (aldersResultat is AlderFunnet) aldersResultat.alder else null,
            profileringProvider(fnr),
        )
        KontorTilordningService.tilordneKontor(kontorTilordning)
    }

    private fun hentTilordning(
        fnr: String,
        gtKontor: KontorId?,
        alder: Int?,
        profilering: ProfileringsResultat,
    ): AOKontorEndret {
        if (alder == null) throw IllegalArgumentException("Alder == null")

        if (profilering is ProfileringFunnet &&
            profilering.profilering == ProfileringEnum.ANTATT_GODE_MULIGHETER &&
            alder in 31..59) {
            return OppfolgingsperiodeStartetNoeTilordning(fnr)
        }

        return when {
            gtKontor == null -> OppfolgingsPeriodeStartetLokalKontorTilordning(KontorTilordning(fnr, KontorId("2990")))
            else -> OppfolgingsPeriodeStartetLokalKontorTilordning(KontorTilordning(fnr, gtKontor))
        }
    }
}
