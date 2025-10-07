package http

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import no.nav.configureSecurity
import no.nav.db.Ident
import no.nav.domain.KontorId
import no.nav.security.mock.oauth2.MockOAuth2Server
import no.nav.utils.flywayMigrationInTest
import no.nav.utils.getJsonHttpClient
import no.nav.utils.getMockOauth2ServerConfig
import no.nav.utils.gittIdentIMapping
import no.nav.utils.gittIdentMedKontor
import no.nav.utils.randomFnr
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class HentArbeidsoppfolgingskontorBulkTest {
    val server = MockOAuth2Server()
    suspend fun withMockOAuth2Server(block: suspend MockOAuth2Server.() -> Unit) {
        server.start()
        server.block()
        server.shutdown()
    }

    companion object {
        @JvmStatic
        @BeforeAll
        fun setup() {
            flywayMigrationInTest()
        }
    }

    @Test
    fun `Henter arbeidsoppfolgingskontorBulk`() = testApplication {
        environment {
            config = server.getMockOauth2ServerConfig()
        }
        application {
            configureSecurity()
            hentArbeidsoppfolgingskontorModule(services.KontorTilhorighetBulkService)
        }
        val httpClient = getJsonHttpClient()
        val bruker = randomFnr()
        val kontor = KontorId("2112")
        gittIdentIMapping(bruker)
        gittIdentMedKontor(bruker, kontor)

        withMockOAuth2Server {
            val result = httpClient.hentKontorBulk(server, listOf(bruker))

            result.status shouldBe HttpStatusCode.MultiStatus
            val resultBody = result.body<List<BulkKontorOutboundDto>>()

            resultBody shouldHaveSize 1
            resultBody.first() shouldBe BulkKontorOutboundDto(
                bruker.value,
                200,
                kontor.id
            )
        }
    }
}


suspend fun HttpClient.hentKontorBulk(server: MockOAuth2Server, identer: List<Ident>): HttpResponse {
    return post(tilhorighetBulkRoutePath) {
        header("Authorization", "Bearer ${server.issueToken(
            claims = mapOf(
                "roles" to "bulk-hent-kontor",
            )
        ).serialize()}")
        header("Content-Type", "application/json")
        header("Accept", "application/json")
        setBody(BulkKontorInboundDto(
            identer = identer.map { it.value },
        ))
    }
}