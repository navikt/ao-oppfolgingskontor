package no.nav

import io.kotest.matchers.shouldBe
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import no.nav.graphql.configureGraphQlModule
import no.nav.security.mock.oauth2.MockOAuth2Server
import no.nav.utils.getJsonClient
import org.junit.Test

class AuthenticationTest {

    fun ApplicationTestBuilder.setupTestAppWithAuthAndGraphql() {
        environment {
            config = getMockOauth2ServerConfig()
        }
        application {
            configureSecurity()
            configureGraphQlModule()
        }
    }

    @Test
    fun `graphql endepunkter skal kreve gyldig token`() = testApplication {
        server.start()
        setupTestAppWithAuthAndGraphql()
        val client = getJsonClient()

        val response = client.post("/graphql") {
            header("Authorization", "Bearer ${server.issueToken().serialize()}")
            header("Content-Type", "application/json")
            setBody("{\"query\":\"{kontorForBruker {kontorId}}\"}")
        }

        response.status shouldBe HttpStatusCode.OK
        server.shutdown()
    }

    @Test
    fun `skal gi 403 ved ugyldig token på graphql`() = testApplication {
        server.start()
        setupTestAppWithAuthAndGraphql()
        val client = getJsonClient()

        val response = client.post("/graphql") {
            header("Content-Type", "application/json")
            setBody("{\"query\":\"{kontorForBruker {kontorId}}\"}")
        }

        response.status shouldBe HttpStatusCode.Unauthorized
        server.shutdown()
    }

    /* Default issuer is "default" and default aud is "default" */
    val server = MockOAuth2Server()
    private fun getMockOauth2ServerConfig(
        acceptedIssuer: String = "default",
        acceptedAudience: String = "default"): MapApplicationConfig {
        return MapApplicationConfig().apply {
            put("no.nav.security.jwt.issuers.size", "1")
            put("no.nav.security.jwt.issuers.0.issuer_name", acceptedIssuer)
            put("no.nav.security.jwt.issuers.0.discoveryurl", "${server.wellKnownUrl(acceptedIssuer)}")
            put("no.nav.security.jwt.issuers.0.accepted_audience", acceptedAudience)
        }
    }
}
