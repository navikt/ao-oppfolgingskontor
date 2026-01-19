package services

import domain.gtForBruker.GtForBrukerIkkeFunnet
import domain.gtForBruker.GtNummerForBrukerFunnet
import domain.gtForBruker.GtSomKreverFallback
import domain.kontorForGt.ArbeidsgiverFallbackKontorForGt
import domain.kontorForGt.KontorForGtFantIkkeKontor
import domain.kontorForGt.KontorForGtFantKontorForArbeidsgiverAdresse
import domain.kontorForGt.KontorForGtFeil
import http.client.*
import no.nav.db.IdentSomKanLagres
import no.nav.domain.HarSkjerming
import no.nav.domain.HarStrengtFortroligAdresse
import no.nav.http.client.GeografiskTilknytningBydelNr
import no.nav.http.client.GeografiskTilknytningKommuneNr
import no.nav.http.client.GeografiskTilknytningNr
import no.nav.http.client.NorgKontorForGtFantIkkeKontor
import no.nav.http.client.NorgKontorForGtFantKontor
import no.nav.http.client.NorgKontorForGtFeil
import no.nav.http.client.NorgKontorForGtResultat
import no.nav.http.log

class KontorForBrukerMedMangelfullGtService(
    val hentArbeidsforhold: suspend (ident: IdentSomKanLagres) -> AaregResult,
    val hentArbeidsgiverAdresse: suspend (orgNummer: OrgNummer) -> EregResult,
    val hentKontorForGt: suspend (gt: GeografiskTilknytningNr, strengtFortroligAdresse: HarStrengtFortroligAdresse, skjermet: HarSkjerming) -> NorgKontorForGtResultat,
) {

    suspend fun finnKontorForGtBasertPåArbeidsforhold(
        ident: IdentSomKanLagres,
        mangelfullGt: GtSomKreverFallback,
        harStrengtFortroligAdresse: HarStrengtFortroligAdresse,
        harSkjerming: HarSkjerming
    ): ArbeidsgiverFallbackKontorForGt {
        val arbeidsforhold = when (val res = hentArbeidsforhold(ident)) {
            is AaregFailure -> return KontorForGtFeil("Feil ved henting av arbeidsforhold for bruker med mangelfull gt: " + res.errorMessage)
            is AaregSuccess -> res.data
        }

        log.info("Hentet antall arbeidsforhold: ${arbeidsforhold.size}")

        val orgNummer = arbeidsforhold.partition { it.ansettelsesperiode.sluttdato != null }
            .let { (nåværendeArbeidsforhold, tidligereArbeidsforhold) ->
                nåværendeArbeidsforhold.maxByOrNull { it.ansettelsesperiode.startdato }
                    ?: tidligereArbeidsforhold.maxByOrNull { it.ansettelsesperiode.startdato }
            }
            ?.arbeidssted?.identer?.first { it.type == "ORGANISASJONSNUMMER" }
            ?.ident
            ?.let { OrgNummer(it) }
            ?: return KontorForGtFantIkkeKontor(
                harSkjerming,
                harStrengtFortroligAdresse,
                GtForBrukerIkkeFunnet("Fant ikke noe arbeidsgiverforhold på bruker og derfor ingen fallback-gt")
            )

        log.info("Orgnummer hentet fra arbeidsforhold: $orgNummer")

        val arbeidsgiverAdresse = when (val res = hentArbeidsgiverAdresse(orgNummer)) {
            is EregFailure -> return KontorForGtFeil("Feil ved henting av arbeidsgiveradresse for bruker med mangelfull gt: " + res.errorMessage)
            is EregSuccess -> res.data
        }

        val gt = when (arbeidsgiverAdresse.adresse?.kommunenummer?.length) {
            null -> return KontorForGtFantIkkeKontor(
                harSkjerming,
                harStrengtFortroligAdresse,
                GtForBrukerIkkeFunnet("Fant ikke noe GT fra arbeidsgiverforholdet til bruker fordi kommunenr var null")
            )
            4 -> GeografiskTilknytningKommuneNr(arbeidsgiverAdresse.adresse.kommunenummer)
            6 -> GeografiskTilknytningBydelNr(arbeidsgiverAdresse.adresse.kommunenummer)
            else -> return KontorForGtFeil("Kommunenr fra arbeidsgiverforholdet til bruker er ikke gyldig kommunenr: ${arbeidsgiverAdresse.adresse?.kommunenummer}")
        }
        val kontorForGt = hentKontorForGt(gt, harStrengtFortroligAdresse, harSkjerming)
        val kontorId = when (val res = kontorForGt) {
            NorgKontorForGtFantIkkeKontor -> return KontorForGtFantIkkeKontor(
                harSkjerming,
                harStrengtFortroligAdresse,
                GtNummerForBrukerFunnet(gt)
            )
            is NorgKontorForGtFantKontor -> { res.id }
            is NorgKontorForGtFeil -> return KontorForGtFeil("Feil å hente kontor via Norg for gt: ${gt.value}: ${res.message}")
        }
        return KontorForGtFantKontorForArbeidsgiverAdresse(
            kontorId,
            harSkjerming,
            harStrengtFortroligAdresse,
            gt,
            mangelfullGt)
    }
}



@JvmInline
value class OrgNummer(val value: String)