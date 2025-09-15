package no.nav

import com.expediagroup.graphql.server.ktor.graphQLPostRoute
import io.kotest.matchers.shouldBe
import io.ktor.client.call.body
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import no.nav.db.Fnr
import no.nav.domain.KontorType
import no.nav.domain.NavIdent
import no.nav.http.client.mockNorg2Host
import no.nav.http.client.mockPoaoTilgangHost
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
import no.nav.utils.getMockOauth2ServerConfig
import no.nav.utils.gittBrukerUnderOppfolging
import no.nav.utils.issueToken
import no.nav.utils.kontorTilhorighet
import org.junit.jupiter.api.Test
import services.OppfolgingsperiodeService

class SettArbeidsoppfolgingsKontorTest {

    /* application block seems to be run async so have to take block for extra db-setup as param */
    fun ApplicationTestBuilder.setupTestAppWithAuthAndGraphql(
        fnr: Fnr,
        extraDatabaseSetup: Application.() -> Unit = {},
    ) {
        environment {
            config = server.getMockOauth2ServerConfig()
        }
        val norg2Client = mockNorg2Host()
        val poaoTilgangClient = mockPoaoTilgangHost(null)
        val kontorNavnService = KontorNavnService(norg2Client)
        val kontorTilhorighetService = KontorTilhorighetService(kontorNavnService, poaoTilgangClient)
        val oppfolgingsperiodeService = OppfolgingsperiodeService()
        application {
            flywayMigrationInTest()
            extraDatabaseSetup()
            configureSecurity()
            installGraphQl(norg2Client, kontorTilhorighetService, { req -> req.call.authenticateCall(environment.getIssuer()) })
            configureArbeidsoppfolgingskontorModule(
                kontorNavnService,
                kontorTilhorighetService,
                poaoTilgangClient,
                oppfolgingsperiodeService
            )
            routing {
                authenticate("EntraAD") {
                    graphQLPostRoute()
                }
            }
        }
    }

    @Test
    fun `skal kunne sette arbeidsoppfølgingskontor`() = testApplication {
        withMockOAuth2Server {
            val fnr = Fnr("72345678901")
            val kontorId = "4444"
            val veilederIdent = NavIdent("Z990000")
            setupTestAppWithAuthAndGraphql(fnr) {
                gittBrukerUnderOppfolging(fnr)
            }
            val httpClient = getJsonHttpClient()

            val response = httpClient.settKontor(server, fnr = fnr, kontorId = kontorId, navIdent = veilederIdent)

            response.status shouldBe HttpStatusCode.OK
            val readResponse = httpClient.kontorTilhorighet(fnr, server.issueToken(veilederIdent))
            readResponse.status shouldBe HttpStatusCode.OK
            val kontorResponse = readResponse.body<GraphqlResponse<KontorTilhorighet>>()
            kontorResponse.errors shouldBe null
            kontorResponse.data?.kontorTilhorighet?.kontorId shouldBe kontorId
            kontorResponse.data?.kontorTilhorighet?.registrant shouldBe veilederIdent.id
            kontorResponse.data?.kontorTilhorighet?.registrantType shouldBe RegistrantTypeDto.VEILEDER
            kontorResponse.data?.kontorTilhorighet?.kontorType shouldBe KontorType.ARBEIDSOPPFOLGING
        }
    }

    @Test
    fun `skal svare med 409 når bruker ikke er under oppfølging`() = testApplication {
        withMockOAuth2Server {
            val fnr = Fnr("72345678901")
            val kontorId = "4444"
            val veilederIdent = NavIdent("Z990000")
            setupTestAppWithAuthAndGraphql(fnr)
            val httpClient = getJsonHttpClient()

            val response = httpClient.settKontor(server, fnr = fnr, kontorId = kontorId, navIdent = veilederIdent)

            response.status shouldBe HttpStatusCode.Conflict
        }
    }

    suspend fun withMockOAuth2Server(block: suspend MockOAuth2Server.() -> Unit) {
        server.start()
        server.block()
        server.shutdown()
    }

    val server = MockOAuth2Server()
}