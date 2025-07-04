package no.nav.services

import no.nav.db.Fnr
import no.nav.domain.HarSkjerming
import no.nav.domain.HarStrengtFortroligAdresse
import no.nav.domain.KontorId
import no.nav.domain.Sensitivitet
import no.nav.http.client.GeografiskTilknytningNr
import no.nav.http.client.GtForBrukerFunnet
import no.nav.http.client.GtForBrukerResult
import no.nav.http.client.GtForBrukerIkkeFunnet
import no.nav.http.client.GtForBrukerOppslagFeil
import no.nav.http.client.GtLandForBrukerFunnet
import no.nav.http.client.GtNummerForBrukerFunnet
import org.slf4j.LoggerFactory

class GTNorgService(
    private val gtForBrukerProvider: suspend (fnr: Fnr) -> GtForBrukerResult,
    private val kontorForGtProvider: suspend (gt: GeografiskTilknytningNr, strengtFortroligAdresse: HarStrengtFortroligAdresse, skjermet: HarSkjerming) -> GTKontorResultat,
) {
    val log = LoggerFactory.getLogger(this::class.java)

    suspend fun hentGtKontorForBruker(fnr: Fnr, strengtFortroligAdresse: HarStrengtFortroligAdresse, skjermet: HarSkjerming): GTKontorResultat {
        try {
            val gtForBruker = gtForBrukerProvider(fnr)
            return when (gtForBruker) {
//                is GtForBrukerFunnet -> kontorForGtProvider(gtForBruker.gt, strengtFortroligAdresse, skjermet)
                is GtForBrukerIkkeFunnet -> GTKontorFinnesIkke
                is GtForBrukerOppslagFeil -> GTKontorFeil(gtForBruker.message)
                is GtLandForBrukerFunnet -> TODO()
                is GtNummerForBrukerFunnet -> TODO()
                is GtForBrukerFunnet -> TODO()
            }
        } catch (e: Exception) {
            log.error("henting av GT kontor for bruker feilet (hardt!)", e)
            return GTKontorFeil("Klarte ikke hente GT kontor for bruker: ${e.message}")
        }
    }
}

sealed class GTKontorResultat
sealed class GTKontorFunnet(val kontorId: KontorId) : GTKontorResultat()
class GTKontorMedSkjermingFunnet(kontorId: KontorId) : GTKontorFunnet(kontorId)
class GTKontorAdressebeskyttelseFunnet(kontorId: KontorId) : GTKontorFunnet(kontorId)
class GTKontorVanligFunnet(kontorId: KontorId) : GTKontorFunnet(kontorId)
data object GTKontorFinnesIkke : GTKontorResultat()
data class GTKontorFeil(val melding: String) : GTKontorResultat()

fun GTKontorFunnet.sensitivitet(): Sensitivitet {
    return when (this) {
        is GTKontorAdressebeskyttelseFunnet -> Sensitivitet(HarSkjerming(false), HarStrengtFortroligAdresse(true))
        is GTKontorMedSkjermingFunnet -> Sensitivitet(HarSkjerming(true), HarStrengtFortroligAdresse(false))
        is GTKontorVanligFunnet -> Sensitivitet(HarSkjerming(false), HarStrengtFortroligAdresse(false))
    }
}