package http.client

import no.nav.http.client.tokenexchange.ProvideToken

class AaregClient(
    val baseUrl: String,
    val azureTokenProvider: ProvideToken
) {

}