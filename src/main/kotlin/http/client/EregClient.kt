package http.client

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
            json(Json { ignoreUnknownKeys = true; explicitNulls = false })
        }
    }
) {

    val nøkkelInfoUrl = { orgnummer: String -> "/v2/organisasjon/$orgnummer/noekkelinfo" }
    suspend fun hentNøkkelinfoOmArbeidsgiver(orgNummer: OrgNummer): EregResult {
        return runCatching {
            val result = httpClient.get(nøkkelInfoUrl(orgNummer.value))
            result.body<EregNøkkelinfoDto>()
        }.fold(
            onSuccess = { EregSuccess(it) },
            onFailure =  { EregFailure(it.localizedMessage) })
    }

}

sealed class EregResult
class EregSuccess(
    val data: EregNøkkelinfoDto
): EregResult()
class EregFailure(
    val errorMessage: String
): EregResult()

@Serializable
data class EregNøkkelinfoDto(
    val adresse: Adresse? = null
)

@Serializable
class Adresse(
    val kommunenummer: String? = null,
    val adresselinje1: String?,
    val type: String
)