package services

import http.client.*
import no.nav.db.IdentSomKanLagres
import no.nav.http.client.GeografiskTilknytningNr

class KontorForBrukerMedMangelfullGtService(
    val hentArbeidsforhold: (ident: IdentSomKanLagres) -> AaregResult,
    val hentArbeidsgiveradresse: (orgNummer: OrgNummer) -> EregResult,
    val hentKontorForGt: (gt: GeografiskTilknytningNr) -> Unit
) {

    suspend fun finnFallbackGtBasertPåArbeidsforhold(ident: IdentSomKanLagres) {
        val arbeidsforhold = when (val res = hentArbeidsforhold(ident)) {
            is AaregFailure -> TODO()
            is AaregSuccess -> TODO()
        }
        val arbeidsgiverAdresse = when (val res = hentArbeidsgiveradresse(arbeidsforhold)) {
            is EregFailure -> TODO()
            is EregSuccess -> TODO()
        }
        return hentKontorForGt(arbeidsgiverAdresse)
    }
}



@JvmInline
value class OrgNummer(val value: String)

class Arbeidsforhold() {

}