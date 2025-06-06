package no.nav.http.client

import io.ktor.client.HttpClient
import io.ktor.client.request.request
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import no.nav.poao_tilgang.api.dto.request.ErSkjermetPersonBulkRequest
import no.nav.poao_tilgang.api.dto.request.EvaluatePoliciesRequest
import no.nav.poao_tilgang.api.dto.request.HentAdGrupperForBrukerRequest
import no.nav.poao_tilgang.api.dto.response.EvaluatePoliciesResponse
import no.nav.poao_tilgang.api.dto.response.HentAdGrupperForBrukerResponse
import no.nav.poao_tilgang.api.dto.response.TilgangsattributterResponse
import no.nav.poao_tilgang.client_core.NorskIdent
import no.nav.poao_tilgang.client_core.PoaoTilgangClient
import no.nav.poao_tilgang.client_core.PoaoTilgangHttpClient
import no.nav.poao_tilgang.client_core.api.ApiResult
import no.nav.poao_tilgang.client_core.api.ResponseDataApiException
import org.slf4j.LoggerFactory

class PoaoTilgangKtorHttpClient(
    private val client: HttpClient = HttpClient(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun fetch(
        fullUrl: String,
        method: String,
        body: String? = null
    ): ApiResult<String> {
        runBlocking {
            client.request{}
        }
        return ApiResult.success("Simulated response from $fullUrl with method $method")
    }

    val poaoTilgangKtorHttpClient = PoaoTilgangHttpClient(
        baseUrl = "https://poao-tilgang-api.dev.nav.no",
        httpFetch = ::fetch,
        bodyParser = object: PoaoTilgangClient.BodyParser{
            override fun parsePolicyRequestsBody(body: String): ApiResult<EvaluatePoliciesResponse> {
                TODO("Not yet implemented")
            }

            override fun parseHentTilgangsAttributterBody(body: String): ApiResult<TilgangsattributterResponse> {
                try {
                    return Json.decodeFromString<TilgangsattributterResponse>(body).let { ApiResult.success(it) }
                }
                catch (e: Exception) {
                    log.error("Kunne ikke parse tilgangsattributter. Message: ${e.message}")
                    return ApiResult.failure(ResponseDataApiException("Kunne ikke parse tilgangsattributter. Message: ${e.message}"))
                }
            }

            override fun parseErSkjermetPersonBody(body: String): ApiResult<Map<NorskIdent, Boolean>> {
                TODO("Not yet implemented")
            }

            override fun parseHentAdGrupper(body: String): ApiResult<HentAdGrupperForBrukerResponse> {
                TODO("Not yet implemented")
            }
        },
        serializer = object: PoaoTilgangClient.Serializer{
            override fun <I> serializeEvaluatePolicies(body: EvaluatePoliciesRequest<I>): String {
                TODO("Not yet implemented")
            }

            override fun serializeHentAdGrupper(body: HentAdGrupperForBrukerRequest): String {
                TODO("Not yet implemented")
            }

            override fun serializeErSkjermet(body: ErSkjermetPersonBulkRequest): String {
                TODO("Not yet implemented")
            }

        }

    )

}