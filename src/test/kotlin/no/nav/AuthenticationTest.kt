package no.nav.no.nav

import io.kotest.matchers.shouldBe
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import no.nav.configureSecurity
import no.nav.http.client.norg2TestUrl
import no.nav.http.graphql.configureGraphQlModule
import no.nav.security.mock.oauth2.MockOAuth2Server
import no.nav.utils.getJsonClient
import no.nav.utils.kontorTilhorighetQuery
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
        withMockOAuth2Server {
            setupTestAppWithAuthAndGraphql()
            val client = getJsonClient()

            val response = client.post("/graphql") {
                header("Authorization", "Bearer ${server.issueToken().serialize()}")
                header("Content-Type", "application/json")
                setBody(kontorTilhorighetQuery("8989889898"))
            }

            response.status shouldBe HttpStatusCode.Companion.OK
        }
    }


    @Test
    fun `skal gi 401 ved manglende token på graphql`() = testApplication {
        withMockOAuth2Server {
            setupTestAppWithAuthAndGraphql()
            val client = getJsonClient()

            val response = client.post("/graphql") {
                header("Content-Type", "application/json")
                setBody(kontorTilhorighetQuery("8989889898"))
            }

            response.status shouldBe HttpStatusCode.Companion.Unauthorized
        }
    }

    suspend fun withMockOAuth2Server(block: suspend MockOAuth2Server.() -> Unit) {
        server.start()
        server.block()
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
            put("apis.norg2.url", norg2TestUrl)
        }
    }
}