package no.nav.http.client.tokenexchange

import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer

class SystemTokenPluginConfig(
    var tokenProvider:(suspend () -> TexasTokenResponse)? = null,
)

val SystemTokenPlugin = createClientPlugin("SystemTokenPlugin", ::SystemTokenPluginConfig) {
    val tokenProvider: suspend () -> TexasTokenResponse = pluginConfig.tokenProvider ?: error("Token provider must be set in SystemTokenPluginConfig")

    this.client.config {
        install(Auth) {
            bearer {
                loadTokens {
                    val result = tokenProvider()
                    return@loadTokens when (result) {
                        is TexasTokenSuccessResult -> BearerTokens(result.accessToken, null)
                        is TexasTokenFailedResult -> throw IllegalStateException("Failed to fetch token: ${result.errorMessage}")
                    }
                }
            }
        }
    }
}