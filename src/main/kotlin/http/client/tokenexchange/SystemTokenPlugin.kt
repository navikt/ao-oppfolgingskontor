package no.nav.http.client.tokenexchange

import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.HttpHeaders

typealias ProvideToken = suspend () -> TexasTokenResponse

class SystemTokenPluginConfig(
    var tokenProvider: ProvideToken? = null
)

val SystemTokenPlugin = createClientPlugin("SystemTokenPlugin", ::SystemTokenPluginConfig) {
    val tokenProvider: ProvideToken = pluginConfig.tokenProvider ?: error("Token provider must be set in SystemTokenPluginConfig")

    onRequest { requestBuilder, content ->
        val result = tokenProvider()
        when (result) {
            is TexasTokenFailedResult -> {
                throw IllegalStateException("Could not acquire system token ${result.errorMessage}")
            }
            is TexasTokenSuccessResult -> {
                requestBuilder.headers.append(HttpHeaders.Authorization, "Bearer ${result.accessToken}")
            }
        }
    }
}
