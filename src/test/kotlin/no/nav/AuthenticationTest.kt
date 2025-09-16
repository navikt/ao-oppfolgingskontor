package no.nav.no.nav

import com.expediagroup.graphql.server.ktor.graphQLPostRoute
import db.table.IdentMappingTable
import db.table.IdentMappingTable.internIdent
import db.table.InternIdentSequence
import db.table.nextValueOf
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import no.nav.configureSecurity
import no.nav.db.Fnr
import no.nav.domain.NavIdent
import no.nav.getIssuer
import no.nav.http.client.mockNorg2Host
import no.nav.http.client.mockPoaoTilgangHost
import no.nav.http.graphql.installGraphQl
import no.nav.security.mock.oauth2.MockOAuth2Server
import no.nav.services.KontorNavnService
import no.nav.services.KontorTilhorighetService
import no.nav.utils.flywayMigrationInTest
import no.nav.utils.getJsonHttpClient
import no.nav.utils.getMockOauth2ServerConfig
import no.nav.utils.issueToken
import no.nav.utils.kontorTilhorighet
import no.nav.utils.kontorTilhorighetQuery
import io.ktor.server.routing.routing
import no.nav.authenticateCall
import no.nav.db.Ident
import no.nav.http.client.IdenterFunnet
import no.nav.http.client.IdenterIkkeFunnet
import no.nav.utils.randomFnr
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import services.IdentService
import services.toIdentType

class AuthenticationTest {

    fun ApplicationTestBuilder.setupTestAppWithAuthAndGraphql(ident: Ident? = null) {
        environment {
            config = server.getMockOauth2ServerConfig()
        }
        val identService = IdentService({
            if (ident == null) IdenterIkkeFunnet("lol")
            else IdenterFunnet(listOf(ident), ident)
        })
        ident?.let { transaction {
            IdentMappingTable.insert {
                it[IdentMappingTable.identType] = ident.toIdentType()
                it[IdentMappingTable.id] = ident.value
                it[IdentMappingTable.internIdent] = nextValueOf(InternIdentSequence)
                it[IdentMappingTable.historisk] = ident.historisk
            }
        }}
        val poaoTilgangKtorHttpClient = mockPoaoTilgangHost(null)
        val norg2Client = mockNorg2Host()
        val kontorNavnService = KontorNavnService(norg2Client)
        val kontorTilhorighetService = KontorTilhorighetService(kontorNavnService, poaoTilgangKtorHttpClient, identService)
        application {
            flywayMigrationInTest()
            configureSecurity()
            installGraphQl(norg2Client, kontorTilhorighetService, { req -> req.call.authenticateCall(environment.getIssuer()) }, identService)
            routing {
                authenticate("EntraAD") {
                    graphQLPostRoute()
                }
            }
        }
    }

    @Test
    fun `graphql endepunkter skal godta gyldig token`() = testApplication {
        withMockOAuth2Server {
            val fnr = randomFnr()
            setupTestAppWithAuthAndGraphql(fnr)
            val client = getJsonHttpClient()

            val token = server.issueToken(NavIdent("Hei"))

            val response = client.kontorTilhorighet(fnr, token)

            response.status shouldBe HttpStatusCode.Companion.OK
            response.bodyAsText() shouldNotContain "errors"
        }
    }


    @Test
    fun `skal gi 401 ved manglende token på graphql`() = testApplication {
        withMockOAuth2Server {
            setupTestAppWithAuthAndGraphql()
            val client = getJsonHttpClient()

            val response = client.post("/graphql") {
                header("Content-Type", "application/json")
                setBody(kontorTilhorighetQuery(Fnr("89898898980", Ident.HistoriskStatus.AKTIV)))
            }

            response.status shouldBe HttpStatusCode.Companion.Unauthorized
        }
    }

    @Test
    fun `skal gi 401 ved feil aud token på graphql`() = testApplication {
        withMockOAuth2Server {
            setupTestAppWithAuthAndGraphql()
            val client = getJsonHttpClient()

            val response = client.post("/graphql") {
                header("Content-Type", "application/json")
                bearerAuth(server.issueToken(
                    claims = mapOf(
                        "idtyp" to "app",
                        "azp_name" to "cluster:namespace:app",
                    )
                ).serialize())
                setBody(kontorTilhorighetQuery(Fnr("89898898980", Ident.HistoriskStatus.AKTIV)))
            }

            response.status shouldBe HttpStatusCode.Companion.OK
        }
    }

    suspend fun withMockOAuth2Server(block: suspend MockOAuth2Server.() -> Unit) {
        server.start()
        server.block()
        server.shutdown()
    }

    val server = MockOAuth2Server()
}