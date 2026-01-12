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

data class KontorForGtFantIkkeKontor(
    override val skjerming: HarSkjerming,
    override val strengtFortroligAdresse: HarStrengtFortroligAdresse,
    val gtForBruker: GtForBrukerSuccess
): KontorForGtSuccess(skjerming, strengtFortroligAdresse) {
    override fun gt(): GtForBrukerSuccess = gtForBruker
}

sealed class KontorForGtFantKontor(
    open val kontorId: KontorId,
    override val skjerming: HarSkjerming,
    override val strengtFortroligAdresse: HarStrengtFortroligAdresse
) : KontorForGtSuccess(skjerming, strengtFortroligAdresse)

/**
 * Fant match på /navkontor/{geografiskOmråde}
 * */
data class KontorForGtFantDefaultKontor(
    override val kontorId: KontorId,
    override val skjerming: HarSkjerming,
    override val strengtFortroligAdresse: HarStrengtFortroligAdresse,
    val geografiskTilknytningNr: GeografiskTilknytningNr
) : KontorForGtFantKontor(kontorId, skjerming, strengtFortroligAdresse) {
    override fun gt(): GtForBrukerFunnet = GtNummerForBrukerFunnet(geografiskTilknytningNr)
}

/**
 * Fant match på /navkontor/{geografiskOmråde}
 * */
data class KontorForGtFantKontorForArbeidsgiverAdresse(
    override val kontorId: KontorId,
    override val skjerming: HarSkjerming,
    override val strengtFortroligAdresse: HarStrengtFortroligAdresse,
    val geografiskTilknytningNr: GeografiskTilknytningNr
) : KontorForGtFantKontor(kontorId, skjerming, strengtFortroligAdresse) {
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
) : KontorForGtFantKontor(kontorId, skjerming, strengtFortroligAdresse) {
    override fun gt(): GtForBrukerSuccess = when (gtForBruker) {
        is GtForBrukerIkkeFunnet -> gtForBruker
        is GtLandForBrukerFunnet -> gtForBruker
    }
}

data class KontorForGtFeil(val melding: String) : KontorForGtResultat()
