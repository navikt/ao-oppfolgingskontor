package services

import http.client.AaregClient
import http.client.EregClient
import no.nav.db.IdentSomKanLagres
import no.nav.services.GTNorgService

class KontorForBrukerMedMangelfullGtService(
    val gtNorgService: GTNorgService,
    val aaregClient: AaregClient,
    val eregClient: EregClient,
) {

    fun finnFallbackGtBasertPåArbeidsforhold(ident: IdentSomKanLagres) {

        // Finn siste arbeidsforhold

        // Finn ardresse til siste arbeidsforhold

    }

    fun hentSisteArbeidsforhold() {

    }

    fun hentArbeidsgiver(orgNummer: OrgNummer) {

    }
}

@JvmInline
value class OrgNummer(val value: String)

class Arbeidsforhold() {

}