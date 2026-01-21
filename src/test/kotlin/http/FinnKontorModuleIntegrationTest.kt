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
import no.nav.db.Fnr
import no.nav.db.Ident
import no.nav.db.IdentSomKanLagres
import no.nav.domain.*
import no.nav.domain.events.OppfolgingsPeriodeStartetLokalKontorTilordning
import no.nav.http.FinnKontorInputDto
import no.nav.http.FinnKontorOutputDto
import no.nav.http.client.GeografiskTilknytningKommuneNr
import no.nav.http.configureFinnKontorModule
import no.nav.kafka.consumers.KontorEndringer
import no.nav.services.TilordningFeil
import no.nav.services.TilordningResultat
import no.nav.services.TilordningSuccessIngenEndring
import no.nav.services.TilordningSuccessKontorEndret
import org.junit.Test
import java.util.*
import kotlin.test.assertEquals

class FinnKontorModuleIntegrationTest {
    private fun Application.testApp(
        dryRunKontorTilordning: suspend (IdentSomKanLagres, Boolean) -> TilordningResultat,
        kontorNavn: suspend (KontorId) -> KontorNavn
    ) {
        install(ContentNegotiation) { json() }
        install(Authentication) {
            provider("EntraAD") {
                authenticate { context ->
                    context.principal(UserIdPrincipal("testuser"))
                }
            }
        }
        configureFinnKontorModule(dryRunKontorTilordning, kontorNavn)
    }

    @Test
    fun `suksess gir 200 OK og korrekt respons`() = testApplication {
        val kontorId = KontorId("1234")
        val kontorNavn = KontorNavn("NAV Testkontor")
        val ident = Fnr(value = "01018012345", historisk = Ident.HistoriskStatus.UKJENT)
        val event = lagKontorEndretEvent(kontorId, ident)
        application {
            testApp(
                dryRunKontorTilordning = { i, _ ->
                    if (i == ident) TilordningSuccessKontorEndret(event) else TilordningFeil("Feil")
                },
                kontorNavn = { id -> if (id == kontorId) kontorNavn else KontorNavn("Ukjent") }
            )
        }
        val input = FinnKontorInputDto(ident, erArbeidssøker = true)
        val response = client.post("/api/finn-kontor") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(input))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.decodeFromString(FinnKontorOutputDto.serializer(), response.bodyAsText())
        assertEquals(kontorId, json.kontorId)
        assertEquals(kontorNavn, json.kontorNavn)
    }

    @Test
    fun `feil fra ruting gir 500 InternalServerError`() = testApplication {
        val ident = Fnr(value = "01018012345", historisk = Ident.HistoriskStatus.UKJENT)
        application {
            testApp(
                dryRunKontorTilordning = { _, _ -> TilordningFeil("Feil oppstod") },
                kontorNavn = { KontorNavn("Ukjent") }
            )
        }
        val input = FinnKontorInputDto(ident, erArbeidssøker = true)
        val response = client.post("/api/finn-kontor") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(input))
        }
        assertEquals(HttpStatusCode.InternalServerError, response.status)
    }

    @Test
    fun `ingen endring gir 500 InternalServerError`() = testApplication {
        val ident = Fnr(value = "01018012345", historisk = Ident.HistoriskStatus.UKJENT)
        application {
            testApp(
                dryRunKontorTilordning = { _, _ -> TilordningSuccessIngenEndring },
                kontorNavn = { KontorNavn("Ukjent") }
            )
        }
        val input = FinnKontorInputDto(ident, erArbeidssøker = true)
        val response = client.post("/api/finn-kontor") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(input))
        }
        assertEquals(HttpStatusCode.InternalServerError, response.status)
    }

    @Test
    fun `uautentisert gir 401 Unauthorized`() = testApplication {
        val ident = Fnr(value = "01018012345", historisk = Ident.HistoriskStatus.UKJENT)
        application {
            testApp(
                dryRunKontorTilordning = { _, _ -> TilordningSuccessIngenEndring },
                kontorNavn = { KontorNavn("Ukjent") }
            )
        }
        val input = FinnKontorInputDto(ident, erArbeidssøker = true)
        val response = createClient {
        }.post("/api/finn-kontor") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(input))
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
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
}
