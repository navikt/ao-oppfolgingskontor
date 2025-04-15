package no.nav

import com.expediagroup.graphql.server.ktor.graphQLPostRoute
import io.kotest.matchers.shouldBe
import io.ktor.client.call.*
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.*
import io.ktor.server.testing.*
import no.nav.db.Fnr
import no.nav.db.table.ArenaKontorTable
import no.nav.db.table.KontorhistorikkTable
import no.nav.graphql.schemas.KontorQueryDto
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import io.ktor.server.routing.routing
import no.nav.domain.KontorEndringsType
import no.nav.graphql.installGraphQl
import no.nav.graphql.queries.KontorKilde
import no.nav.graphql.schemas.KontorHistorikkQueryDto
import no.nav.utils.GraphqlResponse
import no.nav.utils.KontorForBruker
import no.nav.utils.KontorHistorikk
import no.nav.utils.flywayMigrationInTest
import no.nav.utils.getJsonClient
import java.time.ZonedDateTime
import kotlin.test.Test

class GraphqlApplicationTest {

    fun Application.graphqlServerInTest() {
        installGraphQl()
        routing {
            graphQLPostRoute()
        }
    }

    @Test
    fun `skal kunne hente kontor via graphql`() = testApplication {
        val fnr = "22345678901"
        val kontorId = "4142"

        application {
            flywayMigrationInTest()
            graphqlServerInTest()
            gittBrukerMedKontorIArena(fnr, kontorId)
        }
        val client =  getJsonClient()

        val response = client.post("/graphql") {
            contentType(ContentType.Application.Json)
            setBody("""{"variables": { "fnr": $fnr }, "query": " { kontorForBruker (fnrParam: \"$fnr\") { kontorId , kilde } }"}""")
        }

        response.status shouldBe HttpStatusCode.OK
        val payload = response.body<GraphqlResponse<KontorForBruker>>()
        payload shouldBe GraphqlResponse(KontorForBruker(KontorQueryDto(kontorId, KontorKilde.ARENA)))
    }

    @Test
    fun `skal kunne hente kontorhistorikk via graphql`() = testApplication {
        val fnr = "32345678901"
        val kontorId = "4142"
        application {
            flywayMigrationInTest()
            graphqlServerInTest()
            gittBrukerMedKontorIArena(fnr, kontorId)
        }
        val client = getJsonClient()

        val response = client.post("/graphql") {
            contentType(ContentType.Application.Json)
            setBody("""{"variables": { "fnr": $fnr }, "query": " { kontorHistorikk (fnrParam: \"$fnr\") { kontorId , kilde, endretAv, endretAvType, endretTidspunkt, endringsType } }"}""")
        }

        response.status shouldBe HttpStatusCode.OK
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
