package http.client

import io.ktor.server.application.ApplicationEnvironment
import no.nav.http.client.tokenexchange.ProvideToken

fun ApplicationEnvironment.getAaregScope(): String {
    return config.property("apis.aareg.scope").getString()
}

class AaregClient(
    val baseUrl: String,
    val azureTokenProvider: ProvideToken
) {

}

class Arbeidssted(
    /* Liste av identer for underenhet (organisasjonsnummer) eller person (folkeregisterident/aktør-id) */
    identer: List<String>
)
/* Er en mye større DTO med felter som vi ikke trenger foreløpig */
class ArbeidsforholdDto(
    val arbeidssted: Arbeidssted,
    val sistBekreftet: String,
    val sistEndret: String,
)