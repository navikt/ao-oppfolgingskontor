package no.nav

import com.expediagroup.graphql.server.ktor.graphQLPostRoute
import io.kotest.matchers.shouldBe
import io.ktor.client.call.body
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authentication
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import no.nav.domain.KontorType
import no.nav.domain.NavIdent
import no.nav.http.client.mockNorg2Host
import no.nav.http.client.mockPoaoTilgangHost
import no.nav.http.client.norg2TestUrl
import no.nav.http.client.settKontor
import no.nav.http.configureArbeidsoppfolgingskontorModule
import no.nav.http.graphql.installGraphQl
import no.nav.http.graphql.schemas.RegistrantTypeDto
import no.nav.security.mock.oauth2.MockOAuth2Server
import no.nav.services.KontorNavnService
import no.nav.services.KontorTilhorighetService
import no.nav.utils.GraphqlResponse
import no.nav.utils.KontorTilhorighet
import no.nav.utils.flywayMigrationInTest
import no.nav.utils.getJsonHttpClient
import no.nav.utils.kontorTilhorighet
import org.junit.Test
import java.util.UUID

class SettArbeidsoppfolgingsKontorTest {

    fun ApplicationTestBuilder.setupTestAppWithAuthAndGraphql() {
        environment {
            config = getMockOauth2ServerConfig()
        }

        val norg2Client = mockNorg2Host()
        val kontorNavnService = KontorNavnService(norg2Client)
        val poaoTilgangHttpClient = mockPoaoTilgangHost(null)
        val kontorTilhorighetService = KontorTilhorighetService(kontorNavnService, poaoTilgangHttpClient)
        application {
            flywayMigrationInTest()
            configureSecurity()
            installGraphQl(norg2Client, kontorTilhorighetService, "issuer")
            configureArbeidsoppfolgingskontorModule(
                kontorNavnService,
                kontorTilhorighetService,
                poaoTilgangHttpClient
            )
            routing {
                authentication {
                    graphQLPostRoute()
                }
            }
        }
    }

    @Test
    fun `skal kunne sette arbeidsoppfølgingskontor`() = testApplication {
        withMockOAuth2Server {
            val fnr = "72345678901"
            val kontorId = "4444"
            val veileder = NavAnsatt(NavIdent("Z990000"), UUID.randomUUID())
            setupTestAppWithAuthAndGraphql()
            val httpClient = getJsonHttpClient()

            val response = httpClient.settKontor(server, fnr = fnr, kontorId = kontorId, navAnsatt = veileder)

            response.status shouldBe HttpStatusCode.OK
            val readResponse = httpClient.kontorTilhorighet(fnr)
            readResponse.status shouldBe HttpStatusCode.OK
            val kontorResponse = readResponse.body<GraphqlResponse<KontorTilhorighet>>()
            kontorResponse.errors shouldBe null
            kontorResponse.data?.kontorTilhorighet?.kontorId shouldBe kontorId
            kontorResponse.data?.kontorTilhorighet?.registrant shouldBe veileder.navIdent.id
            kontorResponse.data?.kontorTilhorighet?.registrantType shouldBe RegistrantTypeDto.VEILEDER
            kontorResponse.data?.kontorTilhorighet?.kontorType shouldBe KontorType.ARBEIDSOPPFOLGING
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