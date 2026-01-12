package http.client

import io.ktor.server.application.ApplicationEnvironment
import no.nav.http.client.tokenexchange.ProvideToken
import services.OrgNummer

fun ApplicationEnvironment.getEregScope(): String {
    return config.property("apis.ereg.scope").getString()
}

class EregClient(
    val baseUrl: String,
    val azureTokenProvider: ProvideToken
) {

    fun hentNøkkelinfoForArbeidsgiver(orgNummer: OrgNummer): EregNøkkelinfoDto {

    }

}

data class EregNøkkelinfoDto(
    val adresse: Adresse
)

class Adresse(
    val kommunenummer: String,
)