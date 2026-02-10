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
import domain.gtForBruker.GtNummerForBrukerFunnet
import no.nav.http.client.IdenterFunnet
import no.nav.http.client.mockNorg2Host
import no.nav.http.client.mockPoaoTilgangHost
import no.nav.http.graphql.installGraphQl
import no.nav.http.graphql.schemas.KontorTilhorighetQueryDto
import no.nav.http.graphql.schemas.RegistrantTypeDto
import domain.kontorForGt.KontorForGtFantDefaultKontor
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldNotBe
import no.nav.AOPrincipal
import no.nav.NavAnsatt
import no.nav.http.graphql.schemas.AlleKontorQueryDto
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

fun ApplicationTestBuilder.graphqlServerInTest(
    ident: Ident,
    harTilgang: Boolean = true,
    principal: AOPrincipal = NavAnsatt(
        NavIdent("A112211"),
        UUID.randomUUID()
    )
) {
    val norg2Client = mockNorg2Host()
    val poaoTilgangClient = mockPoaoTilgangHost(null, harTilgang)
    val identer = IdenterFunnet(listOf(ident).map { Ident.validateOrThrow(it.value, AKTIV) }, ident)
    application {
        flywayMigrationInTest()
        installGraphQl(
            norg2Client,
            KontorTilhorighetService(
                KontorNavnService(norg2Client),
                { identer }
            ),
            { Authenticated(principal) },
            { identer },
            poaoTilgangClient::harLeseTilgang
        )
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
    fun `skal svare med 200 og error i payload når veileder ikke har tilgang`() = testApplication {
        val fnr = randomFnr()
        val client = getJsonHttpClient()
        graphqlServerInTest(fnr, harTilgang = false)

        client.kontorTilhorighet(fnr)
            .let { response ->
                response.status shouldBe OK
                val payload = response.body<GraphqlResponse<KontorTilhorighet>>()
                payload.errors.shouldNotBeNull()
                payload.errors shouldHaveSize 1
                payload.data?.kontorTilhorighet shouldBe null
                payload.errors.first().message shouldBe "Exception while fetching data (/kontorTilhorighet) : Bruker har ikke lov å lese kontortilhørighet på denne brukeren"
            }

        client.alleKontorTilhorigheter(fnr)
            .let { response ->
                response.status shouldBe OK
                val payload = response.body<GraphqlResponse<KontorTilhorigheter>>()
                payload.errors.shouldNotBeNull()
                payload.errors shouldHaveSize 1
                payload.data?.kontorTilhorigheter shouldBe null
                payload.errors.first().message shouldBe "Exception while fetching data (/kontorTilhorigheter) : Bruker har ikke lov å lese kontortilhørigheter på denne brukeren"
            }
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
        kontorhistorikk.kontorNavn shouldBe null
        Instant.parse(kontorhistorikk.endretTidspunkt).shouldBeCloseTo(Instant.now(), 500.milliseconds)
    }

    @Test
    fun `skal kunne hente alle kontor i riktig rekkefølge via graphql`() = testApplication {
        val fnr = randomFnr(UKJENT)
        val kontorId = "4142"
        val geografiskKontorId = "1941"
        val client = getJsonHttpClient()
        graphqlServerInTest(fnr)
        application {
            gittBrukerMedKontorIArena(fnr, kontorId)
            gittBrukerMedGeografiskTilknyttetKontor(fnr, geografiskKontorId)
        }

        val response = client.alleKontor(fnr)

        val antallSpesialkontorer = 4
        val antallEgneAnsatteKontorer = 14
        val antallLokalkontorer = 248
        val antallSykefraværskontorer = 3
        response.status shouldBe OK
        val payload = response.body<GraphqlResponse<AlleKontor>>()
        payload.errors shouldBe null
        val kontorer = payload.data!!.alleKontor
        kontorer shouldHaveSize (antallLokalkontorer + antallSpesialkontorer + antallEgneAnsatteKontorer + antallSykefraværskontorer)

        kontorer[0].kontorId shouldBe geografiskKontorId
        kontorer[1] shouldBe AlleKontorQueryDto("4154","Nasjonal oppfølgingsenhet")
        kontorer[2] shouldBe AlleKontorQueryDto("0393","Nav utland og fellestjenester Oslo")
        kontorer shouldContain AlleKontorQueryDto("2103","Nav Vikafossen")
        kontorer shouldContain AlleKontorQueryDto("2990","Nav IT-avdelingen")
        kontorer shouldContain AlleKontorQueryDto("1476","Nav sjukefråværsavdeling region Sunnfjord")
        kontorer shouldContain AlleKontorQueryDto("0491","Arbeidslivssenter Innlandet")
        kontorer shouldContain AlleKontorQueryDto("0676","ROE Regional oppfølgingsenhet Vest-Viken")
        val kontorerEtterSpesialkontorOgGtKontor = kontorer.subList(3, kontorer.size)
        val sorterteKontorer = kontorerEtterSpesialkontorOgGtKontor.sortedBy { it.kontorId.toInt() }
        kontorerEtterSpesialkontorOgGtKontor shouldBe sorterteKontorer
    }

    @Test
    fun `skal ikke sortere GT-kontor hvis det er likt som ao-kontor`() = testApplication {
        val fnr = randomFnr(UKJENT)
        val kontorId = "4142"
        val geografiskKontorId = "4142"
        val client = getJsonHttpClient()
        graphqlServerInTest(fnr)
        application {
            gittBrukerMedKontorIArena(fnr, kontorId)
            gittBrukerMedGeografiskTilknyttetKontor(fnr, geografiskKontorId)
        }

        val response = client.alleKontor(fnr)

        response.status shouldBe OK
        val payload = response.body<GraphqlResponse<AlleKontor>>()
        val kontorer = payload.data!!.alleKontor
        kontorer[2].kontorId shouldNotBe geografiskKontorId
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
        kontorTilordningService.tilordneKontor(
            EndringPaaOppfolgingsBrukerFraArena(
                kontorTilordning = KontorTilordning(fnr, KontorId(kontorId), OppfolgingsperiodeId(UUID.randomUUID())),
                sistEndretIArena = insertTime.toOffsetDateTime()
            ),
            true
        )
    }

    private fun gittBrukerMedGeografiskTilknyttetKontor(fnr: Fnr, kontorId: String) {
        kontorTilordningService.tilordneKontor(
            GTKontorEndret(
                kontorTilordning = KontorTilordning(fnr, KontorId(kontorId), OppfolgingsperiodeId(UUID.randomUUID())),
                kontorEndringsType = KontorEndringsType.FlyttetAvVeileder,
                gt = GtNummerForBrukerFunnet(GeografiskTilknytningBydelNr("3131"))
            ),
            true
        )
    }

    private fun gittBrukerMedAOKontor(fnr: Fnr, kontorId: String) {
        kontorTilordningService.tilordneKontor(
            OppfolgingsPeriodeStartetLokalKontorTilordning(
                kontorTilordning = KontorTilordning(fnr, KontorId(kontorId), OppfolgingsperiodeId(UUID.randomUUID())),
                kontorForGt = KontorForGtFantDefaultKontor(
                    KontorId(kontorId),
                    ingenSensitivitet.skjermet,
                    ingenSensitivitet.strengtFortroligAdresse,
                    geografiskTilknytningNr = GeografiskTilknytningKommuneNr("2121")
                )
            ),
            true
        )
    }
}
