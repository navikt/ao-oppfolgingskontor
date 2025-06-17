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
        if (result !is TexasTokenSuccessResult) {
            throw IllegalStateException("Could not system token")
        }
        requestBuilder.headers.append(HttpHeaders.Authorization, "Bearer ${result.accessToken}")
    }
}
