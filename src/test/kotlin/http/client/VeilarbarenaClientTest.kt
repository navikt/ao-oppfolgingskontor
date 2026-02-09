package http.client

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeTypeOf
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import no.nav.http.client.tokenexchange.TexasTokenSuccessResult
import no.nav.utils.randomFnr
import org.junit.jupiter.api.Test

class VeilarbarenaClientTest {

    @Test
    fun `Skal kunne hente arenaKontor`() {
        runBlocking {
            val fnr = randomFnr()
            val navKontor = "0220"
            val json = Json { ignoreUnknownKeys = false }

            val mockEngine = MockEngine { request ->
                val bodyText = (request.body as TextContent).text
                val jsonBody = json.parseToJsonElement(bodyText).jsonObject
                jsonBody["fnr"]?.jsonPrimitive?.content shouldBe fnr.value

                respond(
                    content = jsonResponse(fnr.value, navKontor),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
            val apiClient = VeilarbArenaClient("", { tokenResponse }, mockEngine)

            val result = apiClient.hentArenaKontor(fnr)

            result.shouldBeTypeOf<ArenakontorFunnet>()
            result.kontorId.id shouldBe navKontor
        }
    }

    private fun jsonResponse(fnr: String, navKontor: String) = """
        {
          "fodselsnr": "$fnr",
          "formidlingsgruppekode": "ARBS",
          "iservFraDato": "2024-04-04T00:00:00+02:00",
          "navKontor": "$navKontor",
          "kvalifiseringsgruppekode": "BATT",
          "rettighetsgruppekode": "INDS",
          "hovedmaalkode": "SKAFFEA",
          "sikkerhetstiltakTypeKode": "TFUS",
          "frKode": "6",
          "harOppfolgingssak": true,
          "sperretAnsatt": false,
          "erDoed": false,
          "doedFraDato": null,
          "sistEndretDato": "2024-04-04T00:00:00+02:00"
        }
    """.trimIndent()

    private val tokenResponse = TexasTokenSuccessResult(
        accessToken = "token",
        expiresIn = 60,
        tokenType = "systemToken"
    )
}