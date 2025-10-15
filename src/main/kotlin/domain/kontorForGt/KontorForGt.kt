package domain.kontorForGt

import domain.gtForBruker.GtForBrukerFunnet
import domain.gtForBruker.GtForBrukerIkkeFunnet
import domain.gtForBruker.GtForBrukerSuccess
import domain.gtForBruker.GtLandForBrukerFunnet
import domain.gtForBruker.GtNummerForBrukerFunnet
import domain.gtForBruker.GtSomKreverFallback
import no.nav.domain.HarSkjerming
import no.nav.domain.HarStrengtFortroligAdresse
import no.nav.domain.KontorId
import no.nav.domain.Sensitivitet
import no.nav.http.client.GeografiskTilknytningNr


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
    val gtForBruker: GtSomKreverFallback
): KontorForGtSuccess(skjerming, strengtFortroligAdresse) {
    override fun gt(): GtForBrukerSuccess = when (gtForBruker) {
        is GtForBrukerIkkeFunnet -> gtForBruker
        is GtLandForBrukerFunnet -> gtForBruker
    }
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
