package no.nav.no.nav

import com.expediagroup.graphql.server.ktor.graphQLPostRoute
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.date.shouldBeCloseTo
import io.kotest.matchers.shouldBe
import io.ktor.client.call.*
import io.ktor.http.HttpStatusCode.Companion.OK
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import no.nav.Authenticated
import no.nav.SystemPrincipal
import no.nav.db.Fnr
import no.nav.db.Ident
import no.nav.db.Ident.HistoriskStatus.AKTIV
import no.nav.db.Ident.HistoriskStatus.UKJENT
import no.nav.domain.*
import no.nav.domain.events.EndringPaaOppfolgingsBrukerFraArena
import no.nav.domain.events.GTKontorEndret
import no.nav.domain.events.OppfolgingsPeriodeStartetLokalKontorTilordning
import no.nav.http.client.GeografiskTilknytningBydelNr
import no.nav.http.client.GeografiskTilknytningKommuneNr
import no.nav.http.client.GtNummerForBrukerFunnet
import no.nav.http.client.IdenterFunnet
import no.nav.http.client.mockNorg2Host
import no.nav.http.client.mockPoaoTilgangHost
import no.nav.http.graphql.installGraphQl
import no.nav.http.graphql.schemas.KontorTilhorighetQueryDto
import no.nav.http.graphql.schemas.RegistrantTypeDto
import no.nav.services.KontorForGtNrFantDefaultKontor
import no.nav.services.KontorNavnService
import no.nav.services.KontorTilhorighetService
import no.nav.services.KontorTilordningService
import no.nav.utils.*
import no.nav.utils.KontorTilhorighet
import org.junit.jupiter.api.Test
import services.ingenSensitivitet
import java.time.Instant
import java.time.ZonedDateTime
import java.util.*
import kotlin.time.Duration.Companion.milliseconds

fun ApplicationTestBuilder.graphqlServerInTest(ident: Ident) {
    val norg2Client = mockNorg2Host()
    val poaoTilgangClient = mockPoaoTilgangHost(null)
    val identer = IdenterFunnet(listOf(ident).map { Ident.validateOrThrow(it.value, AKTIV) }, ident)
    application {
        flywayMigrationInTest()
        installGraphQl(
            norg2Client,
            KontorTilhorighetService(KontorNavnService(norg2Client), poaoTilgangClient, { identer }),
            { Authenticated(SystemPrincipal("lol")) }, { identer })
        routing {
            graphQLPostRoute()
        }
    }
}

class GraphqlApplicationTest {

    @Test
    fun `skal kunne hente kontor via graphql`() = testApplication {
        val fnr = randomFnr()
        val kontorId = "4142"
        val client = getJsonHttpClient()
        graphqlServerInTest(fnr)
        application {
            gittBrukerMedKontorIArena(fnr, kontorId)
        }

        val response = client.kontorTilhorighet(fnr)

        response.status shouldBe OK
        val payload = response.body<GraphqlResponse<KontorTilhorighet>>()
        payload shouldBe GraphqlResponse(
            KontorTilhorighet(
                KontorTilhorighetQueryDto(kontorId, "NAV test", KontorType.ARENA, "Arena", RegistrantTypeDto.ARENA)
            )
        )
    }

    @Test
    fun `skal kunne hente kontorhistorikk via graphql`() = testApplication {
        val fnr = randomFnr(UKJENT)
        val kontorId = "4144"
        val client = getJsonHttpClient()
        graphqlServerInTest(fnr)
        application {
            gittBrukerMedKontorIArena(fnr, kontorId)
        }

        val response = client.kontorHistorikk(fnr)

        response.status shouldBe OK
        val payload = response.body<GraphqlResponse<KontorHistorikk>>()
        payload.errors shouldBe null
        payload.data!!.kontorHistorikk shouldHaveSize 1
        val kontorhistorikk = payload.data.kontorHistorikk.first()
        kontorhistorikk.kontorId shouldBe kontorId
        kontorhistorikk.kontorType shouldBe KontorType.ARENA
        kontorhistorikk.endringsType shouldBe KontorEndringsType.EndretIArena
        kontorhistorikk.endretAv shouldBe System().getIdent()
        Instant.parse(kontorhistorikk.endretTidspunkt).shouldBeCloseTo(Instant.now(), 500.milliseconds)
    }

    @Test
    fun `skal kunne hente alle kontor via graphql`() = testApplication {
        val fnr = randomFnr(UKJENT)
        val kontorId = "4142"
        val client = getJsonHttpClient()
        graphqlServerInTest(fnr)
        application {
            gittBrukerMedKontorIArena(fnr, kontorId)
        }

        val response = client.alleKontor()

        response.status shouldBe OK
        val payload = response.body<GraphqlResponse<AlleKontor>>()
        payload.errors shouldBe null
        payload.data!!.alleKontor shouldHaveSize (248 + 3)
    }

    @Test
    fun `skal få GT kontor på tilhørighet hvis ingen andre kontor er satt via graphql`() = testApplication {
        val fnr = randomFnr(UKJENT)
        val kontorId = "4142"
        val client = getJsonHttpClient()
        graphqlServerInTest(fnr)
        application {
            gittBrukerMedGeografiskTilknyttetKontor(fnr, kontorId)
        }

        val response = client.kontorTilhorighet(fnr)

        response.status shouldBe OK
        val payload = response.body<GraphqlResponse<KontorTilhorighet>>()
        payload.errors shouldBe null
        payload.data!!.kontorTilhorighet?.kontorId shouldBe kontorId
    }

    @Test
    fun `skal kunne hente ao-kontor, arena-kontor og gt-kontor samtidig`() = testApplication {
        val fnr = randomFnr(UKJENT)
        val GTkontorId = "4151"
        val AOKontor = "4152"
        val arenaKontorId = "4150"
        graphqlServerInTest(fnr)
        application {
            gittBrukerMedKontorIArena(fnr, arenaKontorId)
            gittBrukerMedGeografiskTilknyttetKontor(fnr, GTkontorId)
            gittBrukerMedAOKontor(fnr, AOKontor)
        }
        val client = getJsonHttpClient()

        val response = client.alleKontorTilhorigheter(fnr)

        response.status shouldBe OK
        val payload = response.body<GraphqlResponse<KontorTilhorigheter>>()
        payload.errors shouldBe null
        payload.data!!.kontorTilhorigheter.arbeidsoppfolging?.kontorId shouldBe AOKontor
        payload.data.kontorTilhorigheter.arena?.kontorId shouldBe arenaKontorId
        payload.data.kontorTilhorigheter.geografiskTilknytning?.kontorId shouldBe GTkontorId
    }

    @Test
    fun `skal kunne hente ao-kontor, arena-kontor og gt-kontor samtidig selvom alle er null`() = testApplication {
        val fnr = randomFnr(UKJENT)
        graphqlServerInTest(fnr)
        val client = getJsonHttpClient()

        val response = client.alleKontorTilhorigheter(fnr)

        response.status shouldBe OK
        val payload = response.body<GraphqlResponse<KontorTilhorigheter>>()
        payload.errors shouldBe null
        payload.data!!.kontorTilhorigheter.arbeidsoppfolging?.kontorId shouldBe null
        payload.data.kontorTilhorigheter.arena?.kontorId shouldBe null
        payload.data.kontorTilhorigheter.geografiskTilknytning?.kontorId shouldBe null
    }

    private fun gittBrukerMedKontorIArena(fnr: Fnr, kontorId: String, insertTime: ZonedDateTime = ZonedDateTime.now()) {
        KontorTilordningService.tilordneKontor(
            EndringPaaOppfolgingsBrukerFraArena(
                kontorTilordning = KontorTilordning(fnr, KontorId(kontorId), OppfolgingsperiodeId(UUID.randomUUID())),
                sistEndretIArena = insertTime.toOffsetDateTime()
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
                kontorForGt = KontorForGtNrFantDefaultKontor(
                    KontorId(kontorId),
                    ingenSensitivitet.skjermet,
                    ingenSensitivitet.strengtFortroligAdresse,
                    geografiskTilknytningNr = GeografiskTilknytningKommuneNr("2121")
                )
            )
        )
    }
}
