package no.nav

import io.kotest.matchers.shouldBe
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import no.nav.http.configureArbeidsoppfolgingskontorModule
import no.nav.security.mock.oauth2.MockOAuth2Server
import no.nav.utils.flywayMigrationInTest
import no.nav.utils.getJsonClient
import org.junit.Test

class SettArbeidsoppfolgingsKontorTest {

    fun ApplicationTestBuilder.setupTestAppWithAuthAndGraphql() {
        environment {
            config = getMockOauth2ServerConfig()
        }
        application {
            flywayMigrationInTest()
            configureSecurity()
            configureArbeidsoppfolgingskontorModule()
        }
    }

    @Test
    fun `skal kunne lese ut NAVIdent av token`() = testApplication {
        withMockOAuth2Server {
            setupTestAppWithAuthAndGraphql()
            val client = getJsonClient()

            val response = client.post("/api/kontor") {
                header("Authorization", "Bearer ${server.issueToken(
                    claims = mapOf("NAVIdent" to "Z990000")
                ).serialize()}")
                header("Content-Type", "application/json")
                setBody("""{ "kontorId": "4444", "fnr": "12345678901" }""")
            }

            response.status shouldBe HttpStatusCode.OK
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
            put("auth.entraIssuer", acceptedIssuer)
        }
    }
}