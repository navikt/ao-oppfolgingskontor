package no.nav.no.nav

import com.expediagroup.graphql.server.ktor.graphQLPostRoute
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.ktor.client.call.body
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import no.nav.Authenticated
import no.nav.SystemPrincipal
import no.nav.db.Fnr
import no.nav.domain.KontorEndringsType
import no.nav.domain.KontorId
import no.nav.domain.KontorTilordning
import no.nav.domain.KontorType
import no.nav.domain.OppfolgingsperiodeId
import no.nav.domain.System
import no.nav.domain.events.EndringPaaOppfolgingsBrukerFraArena
import no.nav.domain.events.GTKontorEndret
import no.nav.domain.events.OppfolgingsPeriodeStartetLokalKontorTilordning
import no.nav.http.client.GeografiskTilknytningBydelNr
import no.nav.http.client.GtNummerForBrukerFunnet
import no.nav.http.client.mockNorg2Host
import no.nav.http.client.mockPoaoTilgangHost
import no.nav.http.graphql.installGraphQl
import no.nav.http.graphql.schemas.KontorHistorikkQueryDto
import no.nav.http.graphql.schemas.KontorTilhorighetQueryDto
import no.nav.http.graphql.schemas.RegistrantTypeDto
import no.nav.services.KontorNavnService
import no.nav.services.KontorTilhorighetService
import no.nav.services.KontorTilordningService
import no.nav.utils.AlleKontor
import no.nav.utils.GraphqlResponse
import no.nav.utils.KontorTilhorighet
import no.nav.utils.KontorHistorikk
import no.nav.utils.KontorTilhorigheter
import no.nav.utils.alleKontor
import no.nav.utils.alleKontorTilhorigheter
import no.nav.utils.flywayMigrationInTest
import no.nav.utils.getJsonHttpClient
import no.nav.utils.kontorHistorikk
import no.nav.utils.kontorTilhorighet
import org.junit.jupiter.api.Test
import services.ingenSensitivitet
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.UUID

fun ApplicationTestBuilder.graphqlServerInTest() {
    val norg2Client = mockNorg2Host()
    val poaoTilgangClient = mockPoaoTilgangHost(null)
    application {
        flywayMigrationInTest()
        installGraphQl(
            norg2Client,
            KontorTilhorighetService(KontorNavnService(norg2Client), poaoTilgangClient),
            { Authenticated(SystemPrincipal("lol")) })
        routing {
            graphQLPostRoute()
        }
    }
}

class GraphqlApplicationTest {

    @Test
    fun `skal kunne hente kontor via graphql`() = testApplication {
        val fnr = Fnr("22345678901")
        val kontorId = "4142"
        val client = getJsonHttpClient()
        graphqlServerInTest()
        application {
            gittBrukerMedKontorIArena(fnr, kontorId, insertTime)
        }

        val response = client.kontorTilhorighet(fnr)

        response.status shouldBe HttpStatusCode.Companion.OK
        val payload = response.body<GraphqlResponse<KontorTilhorighet>>()
        payload shouldBe GraphqlResponse(
            KontorTilhorighet(
                KontorTilhorighetQueryDto(kontorId, "NAV test", KontorType.ARENA, "Arena", RegistrantTypeDto.ARENA)
            )
        )
    }

    @Test
    fun `skal kunne hente kontorhistorikk via graphql`() = testApplication {
        val fnr = Fnr("32645671901")
        val kontorId = "4144"
        val client = getJsonHttpClient()
        graphqlServerInTest()
        application {
            gittBrukerMedKontorIArena(fnr, kontorId, insertTime)
        }

        val response = client.kontorHistorikk(fnr)

        response.status shouldBe HttpStatusCode.Companion.OK
        val payload = response.body<GraphqlResponse<KontorHistorikk>>()
        payload.errors shouldBe null
        payload.data!!.kontorHistorikk shouldHaveSize 1
        val kontorhistorikk = payload.data.kontorHistorikk.first()
        kontorhistorikk.kontorId shouldBe kontorId
        kontorhistorikk.kontorType shouldBe KontorType.ARENA
        kontorhistorikk.endringsType shouldBe KontorEndringsType.EndretIArena
        kontorhistorikk.endretAv shouldBe kontorId

        payload.data shouldBe KontorHistorikk(
            listOf(
                KontorHistorikkQueryDto(
                    kontorId = kontorId,
                    kontorType = KontorType.ARENA,
                    endringsType = KontorEndringsType.EndretIArena,
                    endretAv = System().getIdent(),
                    endretAvType = System().getType(),
                    endretTidspunkt = insertTime.toString()
                )
            )
        )
    }

    @Test
    fun `skal kunne hente alle kontor via graphql`() = testApplication {
        val fnr = Fnr("32345678901")
        val kontorId = "4142"
        val client = getJsonHttpClient()
        graphqlServerInTest()
        application {
            gittBrukerMedKontorIArena(fnr, kontorId, insertTime)
        }

        val response = client.alleKontor()

        response.status shouldBe HttpStatusCode.Companion.OK
        val payload = response.body<GraphqlResponse<AlleKontor>>()
        payload.errors shouldBe null
        payload.data!!.alleKontor shouldHaveSize (248 + 3)
    }

    @Test
    fun `skal få GT kontor på tilhørighet hvis ingen andre kontor er satt via graphql`() = testApplication {
        val fnr = Fnr("32345678901")
        val kontorId = "4142"
        val client = getJsonHttpClient()
        graphqlServerInTest()
        application {
            gittBrukerMedGeografiskTilknyttetKontor(fnr, kontorId)
        }

        val response = client.kontorTilhorighet(fnr)

        response.status shouldBe HttpStatusCode.Companion.OK
        val payload = response.body<GraphqlResponse<KontorTilhorighet>>()
        payload.errors shouldBe null
        payload.data!!.kontorTilhorighet?.kontorId shouldBe kontorId
    }

    @Test
    fun `skal kunne hente ao-kontor, arena-kontor og gt-kontor samtidig`() = testApplication {
        val fnr = Fnr("62345678901")
        val GTkontorId = "4151"
        val AOKontor = "4152"
        val arenaKontorId = "4150"
        graphqlServerInTest()
        application {
            gittBrukerMedKontorIArena(fnr, arenaKontorId, insertTime)
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

    private fun gittBrukerMedKontorIArena(fnr: Fnr, kontorId: String, insertTime: ZonedDateTime = ZonedDateTime.now()) {
        KontorTilordningService.tilordneKontor(
            EndringPaaOppfolgingsBrukerFraArena(
                tilordning = KontorTilordning(fnr, KontorId(kontorId), OppfolgingsperiodeId(UUID.randomUUID())),
                sistEndretDatoArena = insertTime.toOffsetDateTime()
            )
        )
    }

    private fun gittBrukerMedGeografiskTilknyttetKontor(fnr: Fnr, kontorId: String) {
        KontorTilordningService.tilordneKontor(
            GTKontorEndret(
                kontorTilordning = KontorTilordning(fnr, KontorId(kontorId), OppfolgingsperiodeId(UUID.randomUUID())),
                kontorEndringsType = KontorEndringsType.FlyttetAvVeileder,
                gt = GtNummerForBrukerFunnet(GeografiskTilknytningBydelNr("3131"))
            )
        )
    }

    private fun gittBrukerMedAOKontor(fnr: Fnr, kontorId: String) {
        KontorTilordningService.tilordneKontor(
            OppfolgingsPeriodeStartetLokalKontorTilordning(
                kontorTilordning = KontorTilordning(fnr, KontorId(kontorId), OppfolgingsperiodeId(UUID.randomUUID())),
                ingenSensitivitet
            )
        )
    }
}
