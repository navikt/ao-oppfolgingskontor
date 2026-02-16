package http

import domain.kontorForGt.KontorForGtFantDefaultKontor
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import no.nav.Authenticated
import no.nav.NavAnsatt
import no.nav.db.Fnr
import no.nav.db.Ident
import no.nav.domain.*
import no.nav.domain.events.OppfolgingsPeriodeStartetLokalKontorTilordning
import no.nav.http.FinnKontorInputDto
import no.nav.http.FinnKontorOutputDto
import no.nav.http.client.GeografiskTilknytningKommuneNr
import no.nav.http.configureFinnKontorModule
import no.nav.kafka.consumers.KontorEndringer
import no.nav.services.TilordningFeil
import no.nav.services.TilordningResultat
import no.nav.services.TilordningRetry
import no.nav.services.TilordningSuccessIngenEndring
import no.nav.services.TilordningSuccessKontorEndret
import org.junit.jupiter.api.Test
import java.util.*
import kotlin.test.assertEquals

class FinnKontorModuleIntegrationTest {

    private val testIdent = Fnr(value = "01018012345", historisk = Ident.HistoriskStatus.UKJENT)
    private val testKontorId = KontorId("1234")
    private val testKontorNavn = KontorNavn("NAV Helsfyr")

    @Test
    fun `suksess gir 200 OK og korrekt respons`() = testApplication {
        val event = lagKontorEndretEvent(testKontorId, testIdent)
        application { testApp(TilordningSuccessKontorEndret(event), testKontorNavn) }

        val response = client.post("/api/finn-kontor") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(FinnKontorInputDto(testIdent, erArbeidssøker = true)))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.decodeFromString(FinnKontorOutputDto.serializer(), response.bodyAsText())
        assertEquals(testKontorId.id, json.kontorId)
        assertEquals(testKontorNavn.navn, json.kontorNavn)
    }

    @Test
    fun `TilordningFeil gir 500 InternalServerError`() = testApplication {
        application { testApp(TilordningFeil("Feil oppstod"), testKontorNavn) }

        val response = client.post("/api/finn-kontor") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(FinnKontorInputDto(testIdent, erArbeidssøker = true)))
        }

        assertEquals(HttpStatusCode.InternalServerError, response.status)
    }

    @Test
    fun `TilordningSuccessIngenEndring gir 500 InternalServerError`() = testApplication {
        application { testApp(TilordningSuccessIngenEndring, testKontorNavn) }

        val response = client.post("/api/finn-kontor") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(FinnKontorInputDto(testIdent, erArbeidssøker = true)))
        }

        assertEquals(HttpStatusCode.InternalServerError, response.status)
    }

    @Test
    fun `TilordningRetry gir 500 InternalServerError`() = testApplication {
        application { testApp(TilordningRetry("message"), testKontorNavn) }

        val response = client.post("/api/finn-kontor") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(FinnKontorInputDto(testIdent, erArbeidssøker = true)))
        }

        assertEquals(HttpStatusCode.InternalServerError, response.status)
    }

    private fun lagKontorEndretEvent(kontorId: KontorId, fnr: Fnr): KontorEndringer {
        return KontorEndringer(
            aoKontorEndret = OppfolgingsPeriodeStartetLokalKontorTilordning(
                kontorTilordning = KontorTilordning(fnr, kontorId, OppfolgingsperiodeId(UUID.randomUUID())),
                kontorForGt = KontorForGtFantDefaultKontor(
                    kontorId,
                    HarSkjerming(false),
                    HarStrengtFortroligAdresse(false),
                    GeografiskTilknytningKommuneNr(kontorId.id)
                )
            )
        )
    }

    private fun Application.testApp(
        tilordningResultat: TilordningResultat,
        kontorNavn: KontorNavn
    ) {
        install(ContentNegotiation) { json() }
        /* Denne kreves så lenge ktor-routen ligger under authenticate("EntraAD") */
        install(Authentication) {
            provider("EntraAD") {
                authenticate { context ->
                    context.principal(UserIdPrincipal("testuser"))
                }
            }
        }
        configureFinnKontorModule(
            { _, _ -> tilordningResultat },
            { kontorNavn },
            { Authenticated(NavAnsatt(NavIdent("lol"), UUID.randomUUID())) })
    }
}
