package http.client

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationEnvironment
import no.nav.http.client.tokenexchange.ProvideToken
import no.nav.http.client.tokenexchange.TexasTokenFailedResult
import no.nav.http.client.tokenexchange.TexasTokenSuccessResult
import services.OrgNummer

fun ApplicationEnvironment.getEregScope(): String {
    return config.property("apis.ereg.scope").getString()
}

class EregClient(
    val baseUrl: String,
    val httpClient: HttpClient = HttpClient(CIO) {
        defaultRequest {
            url(baseUrl)
        }
        install(Logging) {
            level = LogLevel.INFO
        }
        install(ContentNegotiation) {
            json()
        }
    }
) {

    val nøkkelInfoUrl = { orgnummer: String -> "/v2/organisasjon/$orgnummer/noekkelinfo" }
    suspend fun hentNøkkelinfoForArbeidsgiver(orgNummer: OrgNummer): EregResult {
        return runCatching {
            val result = httpClient.get(nøkkelInfoUrl(orgNummer.value))
            result.body<EregNøkkelinfoDto>()
        }
            .onSuccess { EregSuccess(it) }
            .onFailure { EregFailure(it.localizedMessage) }
    }

}

sealed class EregResult
class EregSuccess(
    val data: EregNøkkelinfoDto
): EregResult()
class EregFailure(
    val errorMessage: String
): EregResult()

data class EregNøkkelinfoDto(
    val adresse: Adresse
)

class Adresse(
    val kommunenummer: String,
)