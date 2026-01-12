package services

import domain.gtForBruker.GtNummerForBrukerFallbackFunnet
import domain.gtForBruker.GtNummerForBrukerFunnet
import http.client.AaregFailure
import http.client.AaregResult
import http.client.AaregSuccess
import http.client.EregFailure
import http.client.EregResult
import http.client.EregSuccess
import no.nav.db.IdentSomKanLagres
import no.nav.http.client.GeografiskTilknytningNr

class KontorForBrukerMedMangelfullGtService(
    val aaregClient: (ident: IdentSomKanLagres) -> AaregResult,
    val eregClient: (orgNummer: OrgNummer) -> EregResult,
    val kontorForGt: (gt: GeografiskTilknytningNr) -> Unit
) {

    suspend fun finnFallbackGtBasertPåArbeidsforhold(ident: IdentSomKanLagres) {
        val arbeidsforhold = when (val res = aaregClient(ident)) {
            is AaregFailure -> TODO()
            is AaregSuccess -> TODO()
        }
        val arbeidsgiverAdresse = when (val res = eregClient(arbeidsforhold)) {
            is EregFailure -> TODO()
            is EregSuccess -> TODO()
        }
        arbeidsgiverAdresse
    }
}

@JvmInline
value class OrgNummer(val value: String)

class Arbeidsforhold() {

}