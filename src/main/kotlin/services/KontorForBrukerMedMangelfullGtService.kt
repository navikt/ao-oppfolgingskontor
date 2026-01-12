package services

import domain.gtForBruker.GtSomKreverFallback
import domain.kontorForGt.KontorForGtFeil
import domain.kontorForGt.KontorForGtResultat
import http.client.*
import no.nav.db.IdentSomKanLagres
import no.nav.http.client.GeografiskTilknytningNr

class KontorForBrukerMedMangelfullGtService(
    val hentArbeidsforhold: (ident: IdentSomKanLagres) -> AaregResult,
    val hentArbeidsgiverAdresse: (orgNummer: OrgNummer) -> EregResult,
    val hentKontorForGt: (gt: GeografiskTilknytningNr) -> KontorForGtResultat,
) {

    suspend fun finnKontorForGtBasertPåArbeidsforhold(ident: IdentSomKanLagres, mangelfullGt: GtSomKreverFallback): KontorForGtResultat {
        val arbeidsforhold = when (val res = hentArbeidsforhold(ident)) {
            is AaregFailure -> KontorForGtFeil("Feil ved henting av arbeidsforhold for bruker med mangelfull gt: " + res.errorMessage)
            is AaregSuccess -> TODO()
        }
        val arbeidsgiverAdresse = when (val res = hentArbeidsgiverAdresse(arbeidsforhold)) {
            is EregFailure -> KontorForGtFeil("Feil ved henting av arbeidsgiveradresse for bruker med mangelfull gt: " + res.errorMessage)
            is EregSuccess -> TODO()
        }
        return hentKontorForGt(arbeidsgiverAdresse)
    }
}



@JvmInline
value class OrgNummer(val value: String)

class Arbeidsforhold() {

}