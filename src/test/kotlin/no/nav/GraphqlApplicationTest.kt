package no.nav

import com.expediagroup.graphql.server.ktor.graphQLPostRoute
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import no.nav.db.Fnr
import no.nav.db.table.ArenaKontorTable
import no.nav.db.table.KontorhistorikkTable
import no.nav.domain.KontorEndringsType
import no.nav.domain.KontorKilde
import no.nav.http.client.Norg2Client
import no.nav.http.client.alleKontor
import no.nav.http.client.mockNorg2Host
import no.nav.http.graphql.getNorg2Url
import no.nav.http.graphql.installGraphQl
import no.nav.http.graphql.schemas.AlleKontorQueryDto
import no.nav.http.graphql.schemas.KontorHistorikkQueryDto
import no.nav.http.graphql.schemas.KontorQueryDto
import no.nav.utils.AlleKontor
import no.nav.utils.GraphqlResponse
import no.nav.utils.KontorForBruker
import no.nav.utils.KontorHistorikk
import no.nav.utils.alleKontor
import no.nav.utils.flywayMigrationInTest
import no.nav.utils.getJsonClient
import no.nav.utils.kontoHistorikk
import no.nav.utils.kontorForBruker
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Test
import java.time.ZonedDateTime

fun ApplicationTestBuilder.graphqlServerInTest() {
    val norg2Client = mockNorg2Host()
    application {
        flywayMigrationInTest()
        installGraphQl(norg2Client)
        routing {
            graphQLPostRoute()
        }
    }
}

class GraphqlApplicationTest {

    @Test
    fun `skal kunne hente kontor via graphql`() = testApplication {
        val fnr = "22345678901"
        val kontorId = "4142"
        val client = getJsonClient()
        graphqlServerInTest()
        application {
            gittBrukerMedKontorIArena(fnr, kontorId)
        }

        val response = client.kontorForBruker(fnr)

        response.status shouldBe HttpStatusCode.Companion.OK
        val payload = response.body<GraphqlResponse<KontorForBruker>>()
        payload shouldBe GraphqlResponse(KontorForBruker(KontorQueryDto(kontorId, KontorKilde.ARENA)))
    }

    @Test
    fun `skal kunne hente kontorhistorikk via graphql`() = testApplication {
        val fnr = "32345678901"
        val kontorId = "4142"
        val client = getJsonClient()
        graphqlServerInTest()
        application {
            gittBrukerMedKontorIArena(fnr, kontorId)
        }

        val response = client.kontoHistorikk(fnr)

        response.status shouldBe HttpStatusCode.Companion.OK
        val payload = response.body<GraphqlResponse<KontorHistorikk>>()
        payload.errors shouldBe null
        payload.data shouldBe KontorHistorikk(
            listOf(
                KontorHistorikkQueryDto(
                    kontorId = "4142",
                    kilde = KontorKilde.ARBEIDSOPPFOLGING,
                    endringsType = KontorEndringsType.FlyttetAvVeileder,
                    endretAv = "S515151",
                    endretAvType = "veileder",
                    endretTidspunkt = insertTime.toString()
                )
            )
        )
    }

    @Test
    fun `skal kunne hente alle kontor via graphql`() = testApplication {
        graphqlServerInTest()
        val client = getJsonClient()

        val response = client.alleKontor()

        response.status shouldBe HttpStatusCode.Companion.OK
        val payload = response.body<GraphqlResponse<AlleKontor>>()
        payload.errors shouldBe null
        payload.data shouldBe AlleKontor(
            alleKontor.map { AlleKontorQueryDto(it.enhetNr, it.navn) }
        )
    }

    val insertTime = ZonedDateTime.parse("2025-04-15T07:12:14.307878Z")
    private fun gittBrukerMedKontorIArena(fnr: Fnr, kontorId: String) {
        transaction {
            ArenaKontorTable.insert {
                it[id] = fnr
                it[this.kontorId] = kontorId
                it[this.createdAt] = insertTime.toOffsetDateTime()
                it[this.updatedAt] = insertTime.toOffsetDateTime()
            }
            KontorhistorikkTable.insert {
                it[this.fnr] = fnr
                it[this.kontorId] = kontorId
                it[this.kontorendringstype] = KontorEndringsType.FlyttetAvVeileder.name
                it[this.endretAvType] = "veileder"
                it[this.endretAv] = "S515151"
                it[this.createdAt] = insertTime.toOffsetDateTime()
            }
        }
    }
}
