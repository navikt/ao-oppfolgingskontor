package no.nav.http.client

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.ApplicationEnvironment
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


fun ApplicationEnvironment.getNaisTokenExchangeEndpoint(): String {
    return config.property("auth.naisTokenExchangeEndpoint").getString()
}

class TexasClient(
    private val tokenEndpoint: String,
    private val httpClient: HttpClient = HttpClient() {
        install(Logging)
        install(ContentNegotiation) {
            json()
        }
    }
) {
    suspend fun getToken(): TexasTokenResponse {
        val texasTokenRequest = TexasTokenRequest(
            identityProvider = "azuread"
        )
        val response =
            httpClient.post(tokenEndpoint) {
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                setBody(texasTokenRequest)
            }

        if (!response.status.isSuccess()) {
            return TexasTokenFailedResult("Kall for autentisering mot Texas feilet")
        }
        return response.body<TexasTokenSuccessResult>()
    }
}

@Serializable
data class TexasTokenRequest(
    @SerialName("identity_provider") val identityProvider: String
)

sealed class TexasTokenResponse
data class TexasTokenFailedResult(val errorMessage: String) : TexasTokenResponse()
data class TexasTokenSuccessResult(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresIn: Int,
    @SerialName("token_type") val tokenType: String,
) : TexasTokenResponse()