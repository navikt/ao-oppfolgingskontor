package no.nav.utils

import io.ktor.server.config.MapApplicationConfig
import no.nav.domain.NavIdent
import no.nav.http.client.norg2TestUrl
import no.nav.security.mock.oauth2.MockOAuth2Server

/* Default issuer is "default" and default aud is "default" */
fun MockOAuth2Server.getMockOauth2ServerConfig(
    acceptedIssuer: String = "default",
    acceptedAudience: String = "default"): MapApplicationConfig {
    val server = this
    return MapApplicationConfig().apply {
        put("no.nav.security.jwt.issuers.size", "1")
        put("no.nav.security.jwt.issuers.0.issuer_name", acceptedIssuer)
        put("no.nav.security.jwt.issuers.0.discoveryurl", "${server.wellKnownUrl(acceptedIssuer)}")
        put("no.nav.security.jwt.issuers.0.accepted_audience", acceptedAudience)
        put("apis.norg2.url", norg2TestUrl)
    }
}

fun MockOAuth2Server.issueToken(navIdent: NavIdent) = this.issueToken(
        claims = mapOf(
            "azp_name" to "cluster:namespace:app",
            "NAVident" to "veilederNavIdent",
            "oid" to "12345678-1234-1234-1234-123456789012",
        )).serialize()
