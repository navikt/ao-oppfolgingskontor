package no.nav.services

import domain.gtForBruker.GtForBrukerIkkeFunnet
import domain.gtForBruker.GtForBrukerOppslagFeil
import domain.gtForBruker.GtForBrukerResult
import domain.gtForBruker.GtLandForBrukerFunnet
import domain.gtForBruker.GtNummerForBrukerFunnet
import domain.gtForBruker.GtSomKreverFallback
import domain.kontorForGt.ArbeidsgiverFallbackKontorForGt
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
import no.nav.http.client.NorgKontorForGtFantIkkeKontor
import no.nav.http.client.NorgKontorForGtFantKontor
import no.nav.http.client.NorgKontorForGtFeil
import no.nav.http.client.NorgKontorForGtResultat
import org.slf4j.LoggerFactory

class GTNorgService(
    private val hentGtForBruker: suspend (fnr: Ident) -> GtForBrukerResult,
    private val hentKontorForGt: suspend (gt: GeografiskTilknytningNr, strengtFortroligAdresse: HarStrengtFortroligAdresse, skjermet: HarSkjerming) -> NorgKontorForGtResultat,
    private val hentKontorForBrukerMedMangelfullGtNorgFallback: suspend (gt: GtSomKreverFallback, strengtFortroligAdresse: HarStrengtFortroligAdresse, skjermet: HarSkjerming) -> KontorForBrukerMedMangelfullGtResultat,
    private val hentKontorForBrukerMedMangelfullGtArbeidsgiverAdresseFallback: suspend (ident: IdentSomKanLagres, gt: GtSomKreverFallback, strengtFortroligAdresse: HarStrengtFortroligAdresse, skjermet: HarSkjerming) -> ArbeidsgiverFallbackKontorForGt,
) {
    val log = LoggerFactory.getLogger(this::class.java)

    suspend fun hentGtKontorForBruker(
        ident: IdentSomKanLagres,
        strengtFortroligAdresse: HarStrengtFortroligAdresse,
        skjermet: HarSkjerming
    ): KontorForGtResultat {
        try {
            val gtForBruker = hentGtForBruker(ident)
            return when (gtForBruker) {
                is GtLandForBrukerFunnet,
                is GtForBrukerIkkeFunnet -> {
                    val gtForBrukerBasertPaArbeidsgiverResult = hentKontorForBrukerMedMangelfullGtArbeidsgiverAdresseFallback(
                        ident,
                        gtForBruker,
                        strengtFortroligAdresse,
                        skjermet)
                    when (gtForBrukerBasertPaArbeidsgiverResult) {
                        is KontorForGtFantKontorForArbeidsgiverAdresse,
                        is KontorForGtFeil -> return gtForBrukerBasertPaArbeidsgiverResult
                        is KontorForGtFantIkkeKontor -> { log.info("Fant ikke kontor for arbeidsgiver adresse")} // Gå videre til neste fallback
                    }

                    val fallbackResult = hentKontorForBrukerMedMangelfullGtNorgFallback(gtForBruker, strengtFortroligAdresse, skjermet)
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
                is GtNummerForBrukerFunnet -> hentKontorForGt(gtForBruker.gtNr, strengtFortroligAdresse, skjermet)
                    .let {
                        when (it) {
                            NorgKontorForGtFantIkkeKontor -> KontorForGtFantIkkeKontor(
                                skjermet,
                                strengtFortroligAdresse,
                                gtForBruker
                            )
                            is NorgKontorForGtFeil -> KontorForGtFeil(it.message)
                            is NorgKontorForGtFantKontor -> KontorForGtFantDefaultKontor(
                                it.id,
                                skjermet,
                                strengtFortroligAdresse,
                                gtForBruker.gtNr
                            )
                        }
                    }
            }
        } catch (e: Exception) {
            log.error("henting av GT kontor for bruker feilet (hardt!)", e)
            return KontorForGtFeil("Klarte ikke hente GT kontor for bruker: ${e.message}")
        }
    }
}
