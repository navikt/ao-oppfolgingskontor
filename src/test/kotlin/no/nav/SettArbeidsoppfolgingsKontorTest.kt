package no.nav

import com.expediagroup.graphql.server.ktor.graphQLPostRoute
import io.kotest.matchers.shouldBe
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.auth.authentication
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import no.nav.http.client.mockNorg2Host
import no.nav.http.client.norg2TestUrl
import no.nav.http.client.settKontor
import no.nav.http.configureArbeidsoppfolgingskontorModule
import no.nav.http.graphql.installGraphQl
import no.nav.security.mock.oauth2.MockOAuth2Server
import no.nav.utils.GraphqlResponse
import no.nav.utils.KontorForBruker
import no.nav.utils.flywayMigrationInTest
import no.nav.utils.getJsonClient
import no.nav.utils.kontorForBruker
import org.junit.Test

class SettArbeidsoppfolgingsKontorTest {

    fun ApplicationTestBuilder.setupTestAppWithAuthAndGraphql() {
        environment {
            config = getMockOauth2ServerConfig()
        }

        val norg2Client = mockNorg2Host()
        application {
            flywayMigrationInTest()
            configureSecurity()
            installGraphQl(norg2Client)
            configureArbeidsoppfolgingskontorModule()
            routing {
                authentication {
                    graphQLPostRoute()
                }
            }
        }
    }

    @Test
    fun `skal kunne lese ut NAVIdent av token`() = testApplication {
        withMockOAuth2Server {
            val fnr = "72345678901"
            val kontorId = "4444"
            setupTestAppWithAuthAndGraphql()

            val client = getJsonClient()

            val response = client.settKontor(server, fnr = fnr, kontorId = kontorId, navIdent = "Z990000")
            response.status shouldBe HttpStatusCode.OK

            val readResponse = client.kontorForBruker(fnr)
            readResponse.status shouldBe HttpStatusCode.OK
            val kontorResponse = readResponse.body<GraphqlResponse<KontorForBruker>>()
            kontorResponse.errors shouldBe null
            kontorResponse.data?.kontorForBruker?.kontorId shouldBe kontorId
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
            put("apis.norg2.url", norg2TestUrl)
        }
    }
}