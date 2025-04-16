package no.nav.no.nav

import com.expediagroup.graphql.server.ktor.graphQLPostRoute
import io.kotest.matchers.shouldBe
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.*
import io.ktor.server.config.*
import io.ktor.server.response.*
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import no.nav.db.Fnr
import no.nav.db.table.ArenaKontorTable
import no.nav.db.table.KontorhistorikkTable
import no.nav.domain.KontorEndringsType
import no.nav.domain.KontorKilde
import no.nav.http.client.NorgKontor
import no.nav.http.graphql.installGraphQl
import no.nav.http.graphql.schemas.AlleKontorQueryDto
import no.nav.http.graphql.schemas.KontorHistorikkQueryDto
import no.nav.http.graphql.schemas.KontorQueryDto
import no.nav.utils.AlleKontor
import no.nav.utils.GraphqlResponse
import no.nav.utils.KontorForBruker
import no.nav.utils.KontorHistorikk
import no.nav.utils.alleKontorQuery
import no.nav.utils.flywayMigrationInTest
import no.nav.utils.getJsonClient
import no.nav.utils.kontorForBrukerQuery
import no.nav.utils.kontorHistorikkQuery
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Test
import java.time.ZonedDateTime

class GraphqlApplicationTest {

    fun Application.graphqlServerInTest() {
        (environment.config as MapApplicationConfig).apply {
            put("apis.norg2.url", "https://norg2.intern.nav.no")
        }
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
        val client = getJsonClient()

        val response = client.post("/graphql") {
            contentType(ContentType.Application.Json)
            setBody(kontorForBrukerQuery(fnr))
        }

        response.status shouldBe HttpStatusCode.Companion.OK
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
            setBody(kontorHistorikkQuery(fnr))
        }

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
        application {
            flywayMigrationInTest()
            graphqlServerInTest()

//            gittBrukerMedKontorIArena(fnr, kontorId)
        }
        externalServices {
            hosts("https://norg2.intern.nav.no") {
                routing {
                    get("api/v1/enhet") {
                        val fileContent = javaClass.getResource("/norg2enheter.json")?.readText()
                            ?: throw IllegalStateException("File norg2enheter.json not found")
                        call.respondText(fileContent, ContentType.Application.Json)

                    }
                }
            }
        }

        val client = getJsonClient()

        val response = client.post("/graphql") {
            contentType(ContentType.Application.Json)
            setBody(alleKontorQuery())
        }

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

val alleKontor = listOf(
    NorgKontor(
        enhetId = 1,
        enhetNr = "4142",
        navn = "NAV Oslo",
        type = "LOKAL",
        antallRessurser = 10,
        status = "AKTIV",
        orgNivaa = "NAV_KONTOR",
        organisasjonsnummer = "123456789",
        underEtableringDato = null,
        aktiveringsdato = null,
        underAvviklingDato = null,
        nedleggelsesdato = null,
        oppgavebehandler = true,
        versjon = 1,
        sosialeTjenester = null,
        kanalstrategi = null,
        orgNrTilKommunaltNavKontor = null
    )
)