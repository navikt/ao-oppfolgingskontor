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
import no.nav.http.client.FnrIkkeFunnet
import no.nav.http.client.FnrOppslagFeil
import no.nav.http.client.FnrResult
import no.nav.http.client.GTKontorFeil
import no.nav.http.client.GTKontorFunnet
import no.nav.http.client.GTKontorResultat
import no.nav.http.client.arbeidssogerregisteret.ProfileringEnum

sealed class ProfileringsResultat
data class ProfileringFunnet(val profilering: ProfileringEnum) : ProfileringsResultat()
data class ProfileringIkkeFunnet(val melding: String) : ProfileringsResultat()
data class ProfileringsResultatFeil(val error: Throwable) : ProfileringsResultat()

sealed class TilordningResultat
object TilordningSuccess: TilordningResultat()
data class TilordningFeil(val message: String) : TilordningResultat()


class AutomatiskKontorRutingService(
    private val gtKontorProvider: suspend (fnr: String) -> GTKontorResultat,
    private val aldersProvider: suspend (fnr: String) -> AlderResult,
    private val fnrProvider: suspend (aktorId: String) -> FnrResult,
    private val profileringProvider: suspend (fnr: String) -> ProfileringsResultat,
) {
    val log = org.slf4j.LoggerFactory.getLogger(this::class.java)

    suspend fun tilordneKontorAutomatisk(aktorId: String): TilordningResultat {
        try {
            val fnrResult = fnrProvider(aktorId)
            val fnr = when (fnrResult) {
                is FnrFunnet -> fnrResult.fnr
                is FnrIkkeFunnet -> return TilordningFeil("Fant ikke fnr: ${fnrResult.message}")
                is FnrOppslagFeil -> return TilordningFeil("Feil ved oppslag på fnr: ${fnrResult.message}")
            }

            val gtKontorResultat = gtKontorProvider(fnr)
            if (gtKontorResultat is GTKontorFeil) return TilordningFeil("Feil ved henting av gt-kontor: ${gtKontorResultat.melding}")

            val aldersResultat = aldersProvider(fnr)

            val kontorTilordning = hentTilordning(
                fnr,
                (gtKontorResultat as GTKontorFunnet).kontorId,
                if (aldersResultat is AlderFunnet) aldersResultat.alder else null,
                profileringProvider(fnr),
            )
            KontorTilordningService.tilordneKontor(kontorTilordning)
            return TilordningSuccess
        } catch (e: Exception) {
            log.error("Feil ved tilordning kontor: ${e.message}", e)
            return TilordningFeil("Feil ved tilordning av kontor: ${e.message ?: e.toString()}")
        }
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
