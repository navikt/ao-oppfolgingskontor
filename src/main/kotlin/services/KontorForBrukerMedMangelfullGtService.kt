package services

import http.client.AaregClient
import http.client.EregClient
import no.nav.services.GTNorgService

class KontorForBrukerMedMangelfullGtService(
    val gtNorgService: GTNorgService,
    val aaregClient: AaregClient,
    val eregClient: EregClient,
) {

}