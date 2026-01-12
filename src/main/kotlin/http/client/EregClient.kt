package http.client

import io.ktor.server.application.ApplicationEnvironment
import no.nav.http.client.tokenexchange.ProvideToken

fun ApplicationEnvironment.getEregScope(): String {
    return config.property("apis.ereg.scope").getString()
}

class EregClient(
    val baseUrl: String,
    val azureTokenProvider: ProvideToken
) {


}