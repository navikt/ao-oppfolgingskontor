package services

import domain.gtForBruker.GtSomKreverFallback
import domain.kontorForGt.KontorForGtFeil
import domain.kontorForGt.KontorForGtResultat
import http.client.*
import no.nav.db.IdentSomKanLagres
import no.nav.domain.HarSkjerming
import no.nav.domain.HarStrengtFortroligAdresse
import no.nav.http.client.GeografiskTilknytningBydelNr
import no.nav.http.client.GeografiskTilknytningKommuneNr
import no.nav.http.client.GeografiskTilknytningNr

class KontorForBrukerMedMangelfullGtService(
    val hentArbeidsforhold: (ident: IdentSomKanLagres) -> AaregResult,
    val hentArbeidsgiverAdresse: (orgNummer: OrgNummer) -> EregResult,
    val hentKontorForGt: (gt: GeografiskTilknytningNr) -> KontorForGtResultat,
) {

    suspend fun finnKontorForGtBasertPåArbeidsforhold(
        ident: IdentSomKanLagres,
        mangelfullGt: GtSomKreverFallback,
        harStrengtFortroligAdresse: HarStrengtFortroligAdresse,
        harSkjerming: HarSkjerming
    ): KontorForGtResultat {
        val arbeidsforhold = when (val res = hentArbeidsforhold(ident)) {
            is AaregFailure -> return KontorForGtFeil("Feil ved henting av arbeidsforhold for bruker med mangelfull gt: " + res.errorMessage)
            is AaregSuccess -> res.data
        }
        val orgNummer = OrgNummer(arbeidsforhold.arbeidssted.identer.first())
        val arbeidsgiverAdresse = when (val res = hentArbeidsgiverAdresse(orgNummer)) {
            is EregFailure -> return KontorForGtFeil("Feil ved henting av arbeidsgiveradresse for bruker med mangelfull gt: " + res.errorMessage)
            is EregSuccess -> res.data
        }
        val gt = when (arbeidsgiverAdresse.adresse.kommunenummer.length) {
            4 -> GeografiskTilknytningKommuneNr(arbeidsgiverAdresse.adresse.kommunenummer)
            6 -> GeografiskTilknytningBydelNr(arbeidsgiverAdresse.adresse.kommunenummer)
            else -> return KontorForGtFeil("Kunne ikke finne gt basert på arbeidsgiverforhold, feil antall siffer i kommunenr fra ereg")
        }
        return hentKontorForGt(gt)
    }
}



@JvmInline
value class OrgNummer(val value: String)

class Arbeidsforhold() {

}