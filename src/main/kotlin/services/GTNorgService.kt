package no.nav.services

import no.nav.db.Ident
import no.nav.domain.HarSkjerming
import no.nav.domain.HarStrengtFortroligAdresse
import no.nav.domain.KontorId
import no.nav.domain.Sensitivitet
import no.nav.http.client.GeografiskTilknytningNr
import no.nav.http.client.GtForBrukerFunnet
import no.nav.http.client.GtForBrukerResult
import no.nav.http.client.GtForBrukerIkkeFunnet
import no.nav.http.client.GtForBrukerOppslagFeil
import no.nav.http.client.GtForBrukerSuccess
import no.nav.http.client.GtLandForBrukerFunnet
import no.nav.http.client.GtNummerForBrukerFunnet
import no.nav.http.client.GtSomKreverFallback
import org.slf4j.LoggerFactory

class GTNorgService(
    private val gtForBrukerProvider: suspend (fnr: Ident) -> GtForBrukerResult,
    private val kontorForGtProvider: suspend (gt: GeografiskTilknytningNr, strengtFortroligAdresse: HarStrengtFortroligAdresse, skjermet: HarSkjerming) -> KontorForGtResultat,
    private val kontorForBrukerMedMangelfullGt: suspend (gt: GtSomKreverFallback, strengtFortroligAdresse: HarStrengtFortroligAdresse, skjermet: HarSkjerming) -> KontorForBrukerMedMangelfullGtResultat,
) {
    val log = LoggerFactory.getLogger(this::class.java)

    suspend fun hentGtKontorForBruker(
        fnr: Ident,
        strengtFortroligAdresse: HarStrengtFortroligAdresse,
        skjermet: HarSkjerming
    ): KontorForGtResultat {
        try {
            val gtForBruker = gtForBrukerProvider(fnr)
            return when (gtForBruker) {
                is GtLandForBrukerFunnet,
                is GtForBrukerIkkeFunnet -> {
                    when (val fallbackResult =
                        kontorForBrukerMedMangelfullGt(gtForBruker, strengtFortroligAdresse, skjermet)) {
                        is KontorForBrukerMedMangelfullGtFunnet -> KontorForGtNrFantFallbackKontorForManglendeGt(
                            fallbackResult.kontorId,
                            skjermet,
                            strengtFortroligAdresse,
                            gtForBruker
                        )

                        is KontorForBrukerMedMangelfullGtIkkeFunnet -> KontorForGtFinnesIkke(
                            skjermet,
                            strengtFortroligAdresse,
                            gtForBruker
                        )

                        is KontorForBrukerMedMangelfullGtFeil -> KontorForGtFeil(fallbackResult.message)
                    }
                }

                is GtForBrukerOppslagFeil -> KontorForGtFeil(gtForBruker.message)
                is GtNummerForBrukerFunnet -> kontorForGtProvider(gtForBruker.gtNr, strengtFortroligAdresse, skjermet)
            }
        } catch (e: Exception) {
            log.error("henting av GT kontor for bruker feilet (hardt!)", e)
            return KontorForGtFeil("Klarte ikke hente GT kontor for bruker: ${e.message}")
        }
    }
}

/**
 * Når vi får gt med bare land fra PDL propagerer dette videre igjennom kontor-oppslag tjenesten
 */
sealed class KontorForGtResultat

sealed class KontorForGtSuccess(
    open val skjerming: HarSkjerming,
    open val strengtFortroligAdresse: HarStrengtFortroligAdresse
) : KontorForGtResultat() {
    fun erStrengtFortrolig(): Boolean = strengtFortroligAdresse.value
    fun sensitivitet() = Sensitivitet(this.skjerming, this.strengtFortroligAdresse)
    abstract fun gt(): GtForBrukerSuccess
}

data class KontorForGtFinnesIkke(
    override val skjerming: HarSkjerming,
    override val strengtFortroligAdresse: HarStrengtFortroligAdresse,
    val gtForBruker: GtForBrukerSuccess
): KontorForGtSuccess(skjerming, strengtFortroligAdresse) {
    override fun gt(): GtForBrukerSuccess = gtForBruker
}

sealed class KontorForGtNrFantKontor(
    open val kontorId: KontorId,
    override val skjerming: HarSkjerming,
    override val strengtFortroligAdresse: HarStrengtFortroligAdresse
) : KontorForGtSuccess(skjerming, strengtFortroligAdresse)

/**
 * Fant match på /navkontor/{geografiskOmråde}
 * */
data class KontorForGtNrFantDefaultKontor(
    override val kontorId: KontorId,
    override val skjerming: HarSkjerming,
    override val strengtFortroligAdresse: HarStrengtFortroligAdresse,
    val geografiskTilknytningNr: GeografiskTilknytningNr
) : KontorForGtNrFantKontor(kontorId, skjerming, strengtFortroligAdresse) {
    override fun gt(): GtForBrukerFunnet = GtNummerForBrukerFunnet(geografiskTilknytningNr)
}

/**
 * Fallback til arbeidsfordeling/bestmatch
 */
data class KontorForGtNrFantFallbackKontorForManglendeGt(
    override val kontorId: KontorId,
    override val skjerming: HarSkjerming,
    override val strengtFortroligAdresse: HarStrengtFortroligAdresse,
    val gtForBruker: GtSomKreverFallback
) : KontorForGtNrFantKontor(kontorId, skjerming, strengtFortroligAdresse) {
    override fun gt(): GtForBrukerSuccess = when (gtForBruker) {
        is GtForBrukerIkkeFunnet -> gtForBruker
        is GtLandForBrukerFunnet -> gtForBruker
    }
}

data class KontorForGtFeil(val melding: String) : KontorForGtResultat()

/**
 * Noen brukere mangler GT, andre ganger gir ikke GT noen kontor i NORG (http 404)
 * Når det skjer prøver vi arbeidsfordelings tik NORG endepunktet istedet
 */
sealed class KontorForBrukerMedMangelfullGtResultat
data class KontorForBrukerMedMangelfullGtFunnet(val kontorId: KontorId, val gtForBruker: GtSomKreverFallback) :
    KontorForBrukerMedMangelfullGtResultat()

data class KontorForBrukerMedMangelfullGtIkkeFunnet(val gtForBruker: GtSomKreverFallback) :
    KontorForBrukerMedMangelfullGtResultat()

data class KontorForBrukerMedMangelfullGtFeil(val message: String) : KontorForBrukerMedMangelfullGtResultat()
