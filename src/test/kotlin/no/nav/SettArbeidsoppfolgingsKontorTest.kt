package no.nav

import com.expediagroup.graphql.server.ktor.graphQLPostRoute
import io.kotest.matchers.shouldBe
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authentication
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import no.nav.domain.KontorKilde
import no.nav.http.client.logger
import no.nav.http.client.mockNorg2Host
import no.nav.http.client.norg2TestUrl
import no.nav.http.client.settKontor
import no.nav.http.configureArbeidsoppfolgingskontorModule
import no.nav.http.graphql.installGraphQl
import no.nav.security.mock.oauth2.MockOAuth2Server
import no.nav.services.KontorNavnService
import no.nav.services.KontorTilhorighetService
import no.nav.utils.GraphqlResponse
import no.nav.utils.KontorTilhorighet
import no.nav.utils.flywayMigrationInTest
import no.nav.utils.getJsonHttpClient
import no.nav.utils.kontorTilhorighet
import org.junit.Test

class SettArbeidsoppfolgingsKontorTest {

    fun ApplicationTestBuilder.setupTestAppWithAuthAndGraphql() {
        environment {
            config = getMockOauth2ServerConfig()
        }

        val norg2Client = mockNorg2Host()
        val kontorNavnService = KontorNavnService(norg2Client)
        val kontorTilhorighetService = KontorTilhorighetService(kontorNavnService)
        application {
            flywayMigrationInTest()
            configureSecurity()
            installGraphQl(norg2Client, KontorTilhorighetService(KontorNavnService(norg2Client)))
            configureArbeidsoppfolgingskontorModule(
                kontorNavnService,
                kontorTilhorighetService
            )
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

            val httpClient = getJsonHttpClient()

            val response = httpClient.settKontor(server, fnr = fnr, kontorId = kontorId, navIdent = "Z990000")
            response.status shouldBe HttpStatusCode.OK

            val readResponse = httpClient.kontorTilhorighet(fnr)
            readResponse.status shouldBe HttpStatusCode.OK
            val text = readResponse.bodyAsText()
            logger.info("BODY: $text")
            val kontorResponse = readResponse.body<GraphqlResponse<KontorTilhorighet>>()
            kontorResponse.errors shouldBe null
            kontorResponse.data?.kontorTilhorighet?.kontorId shouldBe kontorId
            kontorResponse.data?.kontorTilhorighet?.kilde shouldBe KontorKilde.ARBEIDSOPPFOLGING
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