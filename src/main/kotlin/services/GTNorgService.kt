package no.nav.services

import no.nav.db.Fnr
import no.nav.domain.HarSkjerming
import no.nav.domain.HarStrengtFortroligAdresse
import no.nav.domain.KontorId
import no.nav.domain.Sensitivitet
import no.nav.http.client.GeografiskTilknytningLand
import no.nav.http.client.GeografiskTilknytningNr
import no.nav.http.client.GtForBrukerResult
import no.nav.http.client.GtForBrukerIkkeFunnet
import no.nav.http.client.GtForBrukerOppslagFeil
import no.nav.http.client.GtLandForBrukerFunnet
import no.nav.http.client.GtNummerForBrukerFunnet
import org.slf4j.LoggerFactory

class GTNorgService(
    private val gtForBrukerProvider: suspend (fnr: Fnr) -> GtForBrukerResult,
    private val kontorForGtProvider: suspend (gt: GeografiskTilknytningNr, strengtFortroligAdresse: HarStrengtFortroligAdresse, skjermet: HarSkjerming) -> KontorForGtNrResultat,
) {
    val log = LoggerFactory.getLogger(this::class.java)

    suspend fun hentGtKontorForBruker(fnr: Fnr, strengtFortroligAdresse: HarStrengtFortroligAdresse, skjermet: HarSkjerming): KontorForGtNrResultat {
        try {
            val gtForBruker = gtForBrukerProvider(fnr)
            return when (gtForBruker) {
                is GtForBrukerIkkeFunnet -> KontorForGtFinnesIkke(skjermet, strengtFortroligAdresse)
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
/*
* Når vi får gt mand bare land fra PDL propagerer dette videre igjennom kontor-oppslag tjenesten
* */
sealed class KontorForGtNrResultat
sealed class KontorForGtFantLandEllerKontor(val skjerming: HarSkjerming, val strengtFortroligAdresse: HarStrengtFortroligAdresse) : KontorForGtNrResultat()
data class KontorForGtNrFantKontor(val kontorId: KontorId, val _skjerming: HarSkjerming, val _strengtFortroligAdresse: HarStrengtFortroligAdresse) : KontorForGtFantLandEllerKontor(_skjerming, _strengtFortroligAdresse)
data class KontorForGtNrFantLand(val landkode: GeografiskTilknytningLand, val _skjerming: HarSkjerming, val _strengtFortroligAdresse: HarStrengtFortroligAdresse) : KontorForGtFantLandEllerKontor(_skjerming, _strengtFortroligAdresse)
data class KontorForGtFinnesIkke(val _skjerming: HarSkjerming, val _strengtFortroligAdresse: HarStrengtFortroligAdresse) : KontorForGtFantLandEllerKontor(_skjerming, _strengtFortroligAdresse)
data class KontorForGtNrFeil(val melding: String) : KontorForGtNrResultat()

fun KontorForGtFantLandEllerKontor.sensitivitet(): Sensitivitet {
    return Sensitivitet(
        this.skjerming,
        this.strengtFortroligAdresse
    )
}