package no.nav.services

import domain.gtForBruker.GtForBrukerIkkeFunnet
import domain.gtForBruker.GtForBrukerOppslagFeil
import domain.gtForBruker.GtForBrukerResult
import domain.gtForBruker.GtLandForBrukerFunnet
import domain.gtForBruker.GtNummerForBrukerFunnet
import domain.gtForBruker.GtSomKreverFallback
import domain.kontorForGt.KontorForBrukerMedMangelfullGtFeil
import domain.kontorForGt.KontorForBrukerMedMangelfullGtFunnet
import domain.kontorForGt.KontorForBrukerMedMangelfullGtIkkeFunnet
import domain.kontorForGt.KontorForBrukerMedMangelfullGtResultat
import domain.kontorForGt.KontorForGtFeil
import domain.kontorForGt.KontorForGtFinnesIkke
import domain.kontorForGt.KontorForGtNrFantFallbackKontorForManglendeGt
import domain.kontorForGt.KontorForGtResultat
import no.nav.db.Ident
import no.nav.domain.HarSkjerming
import no.nav.domain.HarStrengtFortroligAdresse
import no.nav.http.client.GeografiskTilknytningNr
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
