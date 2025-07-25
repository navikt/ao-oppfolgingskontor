package no.nav.services

import no.nav.db.Ident
import no.nav.domain.HarSkjerming
import no.nav.domain.HarStrengtFortroligAdresse
import no.nav.domain.KontorId
import no.nav.domain.Sensitivitet
import no.nav.http.client.GeografiskTilknytningLand
import no.nav.http.client.GeografiskTilknytningNr
import no.nav.http.client.GtForBrukerFunnet
import no.nav.http.client.GtForBrukerResult
import no.nav.http.client.GtForBrukerIkkeFunnet
import no.nav.http.client.GtForBrukerOppslagFeil
import no.nav.http.client.GtLandForBrukerFunnet
import no.nav.http.client.GtNummerForBrukerFunnet
import no.nav.http.client.GtType
import org.slf4j.LoggerFactory

class GTNorgService(
    private val gtForBrukerProvider: suspend (fnr: Ident) -> GtForBrukerResult,
    private val kontorForGtProvider: suspend (gt: GeografiskTilknytningNr, strengtFortroligAdresse: HarStrengtFortroligAdresse, skjermet: HarSkjerming) -> KontorForGtNrResultat,
    private val kontorForBrukerMedMangelfullGt: suspend (gt: GtNummerForBrukerFunnet?, strengtFortroligAdresse: HarStrengtFortroligAdresse, skjermet: HarSkjerming) -> KontorForBrukerMedMangelfullGtResultat,
) {
    val log = LoggerFactory.getLogger(this::class.java)

    suspend fun hentGtKontorForBruker(fnr: Ident, strengtFortroligAdresse: HarStrengtFortroligAdresse, skjermet: HarSkjerming): KontorForGtNrResultat {
        try {
            val gtForBruker = gtForBrukerProvider(fnr)
            return when (gtForBruker) {
                is GtForBrukerIkkeFunnet -> {
                    when (val fallbackResult = kontorForBrukerMedMangelfullGt(null, strengtFortroligAdresse, skjermet)) {
                        is KontorForBrukerMedMangelfullGtFunnet -> KontorForGtNrFantFallbackKontor(fallbackResult.kontorId, skjermet, strengtFortroligAdresse, null)
                        is KontorForBrukerMedMangelfullGtIkkeFunnet -> KontorForGtFinnesIkke(skjermet, strengtFortroligAdresse)
                        is KontorForBrukerMedMangelfullGtFeil -> KontorForGtNrFeil(fallbackResult.message)
                    }
                }
                is GtForBrukerOppslagFeil -> KontorForGtNrFeil(gtForBruker.message)
                is GtLandForBrukerFunnet -> KontorForGtNrFantLand(
                    gtForBruker.land,
                    skjermet,
                    strengtFortroligAdresse
                )
                is GtNummerForBrukerFunnet -> kontorForGtProvider(gtForBruker.gt, strengtFortroligAdresse, skjermet)
            }
        } catch (e: Exception) {
            log.error("henting av GT kontor for bruker feilet (hardt!)", e)
            return KontorForGtNrFeil("Klarte ikke hente GT kontor for bruker: ${e.message}")
        }
    }
}
/**
* Når vi får gt med bare land fra PDL propagerer dette videre igjennom kontor-oppslag tjenesten
*/
sealed class KontorForGtNrResultat
sealed class KontorForGtFantLandEllerKontor(open val skjerming: HarSkjerming, open val strengtFortroligAdresse: HarStrengtFortroligAdresse) : KontorForGtNrResultat() {
    fun erStrengtFortrolig(): Boolean = strengtFortroligAdresse.value
    fun sensitivitet() = Sensitivitet(this.skjerming, this.strengtFortroligAdresse)
    abstract fun gt(): GtForBrukerFunnet
}

sealed class KontorForGtNrFantKontor(open val kontorId: KontorId, override val skjerming: HarSkjerming, override val strengtFortroligAdresse: HarStrengtFortroligAdresse) : KontorForGtFantLandEllerKontor(skjerming, strengtFortroligAdresse)
data class KontorForGtNrFantDefaultKontor(override val kontorId: KontorId, override val skjerming: HarSkjerming, override val strengtFortroligAdresse: HarStrengtFortroligAdresse, val geografiskTilknytningNr: GeografiskTilknytningNr) : KontorForGtNrFantKontor(kontorId, skjerming, strengtFortroligAdresse) {
    override fun gt(): GtForBrukerFunnet = GtNummerForBrukerFunnet(geografiskTilknytningNr)
}
data class KontorForGtNrFantFallbackKontor(override val kontorId: KontorId, override val skjerming: HarSkjerming, override val strengtFortroligAdresse: HarStrengtFortroligAdresse, val geografiskTilknytningNr: GeografiskTilknytningNr?) : KontorForGtNrFantKontor(kontorId, skjerming, strengtFortroligAdresse) {
    override fun gt(): GtForBrukerFunnet = throw Exception("asd") // GtNummerForBrukerFunnet(geografiskTilknytningNr)
}
data class KontorForGtNrFantLand(val landkode: GeografiskTilknytningLand, override val skjerming: HarSkjerming, override val strengtFortroligAdresse: HarStrengtFortroligAdresse) : KontorForGtFantLandEllerKontor(skjerming, strengtFortroligAdresse) {
    override fun gt(): GtForBrukerFunnet = GtLandForBrukerFunnet(landkode)
}
data class KontorForGtFinnesIkke(val skjerming: HarSkjerming, val strengtFortroligAdresse: HarStrengtFortroligAdresse) : KontorForGtNrResultat() {
    fun erStrengtFortrolig(): Boolean = strengtFortroligAdresse.value
    fun sensitivitet() = Sensitivitet(this.skjerming, this.strengtFortroligAdresse)
}
data class KontorForGtNrFeil(val melding: String) : KontorForGtNrResultat()

/**
* Noen brukere mangler GT, andre ganger gir ikke GT noen kontor i NORG (http 404)
 */
sealed class KontorForBrukerMedMangelfullGtResultat
data class KontorForBrukerMedMangelfullGtFunnet(val kontorId: KontorId): KontorForBrukerMedMangelfullGtResultat()
data class KontorForBrukerMedMangelfullGtIkkeFunnet(val gtForBruker: GtNummerForBrukerFunnet?): KontorForBrukerMedMangelfullGtResultat()
data class KontorForBrukerMedMangelfullGtFeil(val message: String): KontorForBrukerMedMangelfullGtResultat()
