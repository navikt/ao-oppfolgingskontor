package no.nav.no.nav

import com.expediagroup.graphql.server.ktor.graphQLPostRoute
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.ktor.client.call.body
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import no.nav.db.Fnr
import no.nav.db.table.ArbeidsOppfolgingKontorTable
import no.nav.db.table.ArenaKontorTable
import no.nav.db.table.GeografiskTilknytningKontorTable
import no.nav.db.table.KontorhistorikkTable
import no.nav.domain.KontorEndringsType
import no.nav.domain.KontorKilde
import no.nav.http.client.mockNorg2Host
import no.nav.http.graphql.installGraphQl
import no.nav.http.graphql.schemas.KontorHistorikkQueryDto
import no.nav.http.graphql.schemas.KontorTilhorighetQueryDto
import no.nav.http.graphql.schemas.RegistrantTypeDto
import no.nav.services.KontorNavnService
import no.nav.services.KontorTilhorighetService
import no.nav.utils.AlleKontor
import no.nav.utils.GraphqlResponse
import no.nav.utils.KontorTilhorighet
import no.nav.utils.KontorHistorikk
import no.nav.utils.KontorTilhorigheter
import no.nav.utils.alleKontor
import no.nav.utils.alleKontorTilhorigheter
import no.nav.utils.flywayMigrationInTest
import no.nav.utils.getJsonHttpClient
import no.nav.utils.kontoHistorikk
import no.nav.utils.kontorTilhorighet
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Test
import java.time.ZonedDateTime

fun ApplicationTestBuilder.graphqlServerInTest() {
    val norg2Client = mockNorg2Host()
    application {
        flywayMigrationInTest()
        installGraphQl(norg2Client, KontorTilhorighetService(KontorNavnService(norg2Client)))
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
        val client = getJsonHttpClient()
        graphqlServerInTest()
        application {
            gittBrukerMedKontorIArena(fnr, kontorId)
        }

        val response = client.kontorTilhorighet(fnr)

        response.status shouldBe HttpStatusCode.Companion.OK
        val payload = response.body<GraphqlResponse<KontorTilhorighet>>()
        payload shouldBe GraphqlResponse(KontorTilhorighet(
            KontorTilhorighetQueryDto(kontorId, "NAV test", KontorKilde.ARENA, "Arena", RegistrantTypeDto.ARENA))
        )
    }

    @Test
    fun `skal kunne hente kontorhistorikk via graphql`() = testApplication {
        val fnr = "32645671901"
        val kontorId = "4144"
        val client = getJsonHttpClient()
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
                    kontorId = kontorId,
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
        val fnr = "32345678901"
        val kontorId = "4142"
        val client = getJsonHttpClient()
        graphqlServerInTest()
        application {
            gittBrukerMedKontorIArena(fnr, kontorId)
        }

        val response = client.alleKontor()

        response.status shouldBe HttpStatusCode.Companion.OK
        val payload = response.body<GraphqlResponse<AlleKontor>>()
        payload.errors shouldBe null
        payload.data!!.alleKontor shouldHaveSize 248
    }

    @Test
    fun `skal kunne hente ao-kontor, arena-kontor og gt-kontor samtidig`() = testApplication {
        val fnr = "62345678901"
        val GTkontorId = "4151"
        val AOKontor = "4152"
        val arenaKontorId = "4150"
        graphqlServerInTest()
        application {
            gittBrukerMedKontorIArena(fnr, arenaKontorId)
            gittBrukerMedGeografiskTilknyttetKontor(fnr, GTkontorId)
            gittBrukerMedAOKontor(fnr, AOKontor)
        }
        val client = getJsonHttpClient()

        val response = client.alleKontorTilhorigheter(fnr)

        response.status shouldBe HttpStatusCode.Companion.OK
        val payload = response.body<GraphqlResponse<KontorTilhorigheter>>()
        payload.errors shouldBe null
        payload.data!!.kontorTilhorigheter.arbeidsoppfolging?.kontorId shouldBe AOKontor
        payload.data.kontorTilhorigheter.arena?.kontorId shouldBe arenaKontorId
        payload.data.kontorTilhorigheter.geografiskTilknytning?.kontorId shouldBe GTkontorId
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

    private fun gittBrukerMedGeografiskTilknyttetKontor(fnr: Fnr, kontorId: String) {
        transaction {
            GeografiskTilknytningKontorTable.insert {
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

    private fun gittBrukerMedAOKontor(fnr: Fnr, kontorId: String) {
        transaction {
            ArbeidsOppfolgingKontorTable.insert {
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
