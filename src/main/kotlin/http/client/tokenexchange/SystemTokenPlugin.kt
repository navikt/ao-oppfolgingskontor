package no.nav.http.client.tokenexchange

import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.http.HttpHeaders
import io.ktor.http.headers

typealias ProvideToken = suspend () -> TexasTokenResponse

class SystemTokenPluginConfig(
    var tokenProvider: ProvideToken? = null
)

val SystemTokenPlugin = createClientPlugin("SystemTokenPlugin", ::SystemTokenPluginConfig) {
    val tokenProvider: ProvideToken = pluginConfig.tokenProvider ?: error("Token provider must be set in SystemTokenPluginConfig")

    onRequest { requestBuilder, content ->
        val result = tokenProvider()
        when (result) {
            is TexasTokenSuccessResult -> BearerTokens(result.accessToken, null)
            is TexasTokenFailedResult -> throw IllegalStateException("Failed to fetch token: ${result.errorMessage}")
        }
        headers {
            append(HttpHeaders.Authorization, "Bearer $result")
        }
    }
}