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
import domain.kontorForGt.KontorForGtFantDefaultKontor
import domain.kontorForGt.KontorForGtFeil
import domain.kontorForGt.KontorForGtFantIkkeKontor
import domain.kontorForGt.KontorForGtFantKontorForArbeidsgiverAdresse
import domain.kontorForGt.KontorForGtNrFantFallbackKontorForManglendeGt
import domain.kontorForGt.KontorForGtResultat
import no.nav.db.Ident
import no.nav.db.IdentSomKanLagres
import no.nav.domain.HarSkjerming
import no.nav.domain.HarStrengtFortroligAdresse
import no.nav.http.client.GeografiskTilknytningNr
import org.slf4j.LoggerFactory

class GTNorgService(
    private val gtForBrukerProvider: suspend (fnr: Ident) -> GtForBrukerResult,
    private val kontorForGtProvider: suspend (gt: GeografiskTilknytningNr, strengtFortroligAdresse: HarStrengtFortroligAdresse, skjermet: HarSkjerming) -> KontorForGtResultat,
    private val kontorForBrukerMedMangelfullGtNorgFallback: suspend (gt: GtSomKreverFallback, strengtFortroligAdresse: HarStrengtFortroligAdresse, skjermet: HarSkjerming) -> KontorForBrukerMedMangelfullGtResultat,
    private val kontorForBrukerMedMangelfullGtArbeidsgiverAdresseFallback: suspend (ident: IdentSomKanLagres, gt: GtSomKreverFallback, strengtFortroligAdresse: HarStrengtFortroligAdresse, skjermet: HarSkjerming) -> KontorForGtResultat,
) {
    val log = LoggerFactory.getLogger(this::class.java)

    suspend fun hentGtKontorForBruker(
        ident: IdentSomKanLagres,
        strengtFortroligAdresse: HarStrengtFortroligAdresse,
        skjermet: HarSkjerming
    ): KontorForGtResultat {
        try {
            val gtForBruker = gtForBrukerProvider(ident)
            return when (gtForBruker) {
                is GtLandForBrukerFunnet,
                is GtForBrukerIkkeFunnet -> {
                    val gtForBrukerBasertPaArbeidsgiverResult = kontorForBrukerMedMangelfullGtArbeidsgiverAdresseFallback(ident,gtForBruker, strengtFortroligAdresse, skjermet)
                    when (gtForBrukerBasertPaArbeidsgiverResult) {
                        is KontorForGtFeil -> return gtForBrukerBasertPaArbeidsgiverResult
                        is KontorForGtFantIkkeKontor -> {}
                        is KontorForGtFantDefaultKontor,
                        is KontorForGtFantKontorForArbeidsgiverAdresse,
                        is KontorForGtNrFantFallbackKontorForManglendeGt -> return gtForBrukerBasertPaArbeidsgiverResult
                    }

                    val fallbackResult = kontorForBrukerMedMangelfullGtNorgFallback(gtForBruker, strengtFortroligAdresse, skjermet)
                    when (fallbackResult) {
                        is KontorForBrukerMedMangelfullGtFunnet -> KontorForGtNrFantFallbackKontorForManglendeGt(
                            fallbackResult.kontorId,
                            skjermet,
                            strengtFortroligAdresse,
                            gtForBruker
                        )
                        is KontorForBrukerMedMangelfullGtIkkeFunnet -> KontorForGtFantIkkeKontor(
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
