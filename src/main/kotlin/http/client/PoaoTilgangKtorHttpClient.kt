package no.nav.http.client

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import no.nav.poao_tilgang.api.dto.request.ErSkjermetPersonBulkRequest
import no.nav.poao_tilgang.api.dto.request.EvaluatePoliciesRequest
import no.nav.poao_tilgang.api.dto.request.HentAdGrupperForBrukerRequest
import no.nav.poao_tilgang.api.dto.response.TilgangsattributterResponse
import no.nav.poao_tilgang.client_core.PoaoTilgangClient
import no.nav.poao_tilgang.client_core.PoaoTilgangHttpClient
import no.nav.poao_tilgang.client_core.api.ApiResult
import no.nav.poao_tilgang.client_core.api.ResponseDataApiException
import org.slf4j.LoggerFactory

class PoaoTilgangKtorHttpClient(
    private val baseUrl: String,
    private val client: HttpClient = HttpClient(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun hentTilgangsattributter(ident: String) = poaoTilgangKtorHttpClient.hentTilgangsAttributter(ident)

    private fun fetch(
        fullUrl: String,
        method: String,
        body: String? = null
    ): ApiResult<String> {
        return runBlocking {
            val response = client.request(fullUrl) {
                this.method = HttpMethod.Post
                contentType(ContentType.Application.Json)
                setBody(body)
            }

            if (response.status != HttpStatusCode.OK) {
                log.error("Request to $fullUrl failed with status ${response.status.value} and method $method")
                ApiResult.failure(ResponseDataApiException("Request failed with status ${response.status.value}"))
            } else {
                ApiResult.success(response.bodyAsText())
            }
        }
    }

    private val poaoTilgangKtorHttpClient = PoaoTilgangHttpClient(
        baseUrl,
        httpFetch = ::fetch,
        bodyParser = object : PoaoTilgangClient.BodyParser {

            override fun parseHentTilgangsAttributterBody(body: String): ApiResult<TilgangsattributterResponse> {
                try {
                    return Json.decodeFromString<TilgangsattributterResponse>(body).let { ApiResult.success(it) }
                } catch (e: Exception) {
                    log.error("Kunne ikke parse tilgangsattributter. Message: ${e.message}")
                    return ApiResult.failure(ResponseDataApiException("Kunne ikke parse tilgangsattributter. Message: ${e.message}"))
                }
            }

            override fun parsePolicyRequestsBody(body: String) = TODO("Not yet implemented")
            override fun parseErSkjermetPersonBody(body: String) = TODO("Not yet implemented")
            override fun parseHentAdGrupper(body: String) = TODO("Not yet implemented")
        },
        serializer = object : PoaoTilgangClient.Serializer {
            override fun <I> serializeEvaluatePolicies(body: EvaluatePoliciesRequest<I>) = TODO("Not yet implemented")
            override fun serializeHentAdGrupper(body: HentAdGrupperForBrukerRequest) = TODO("Not yet implemented")
            override fun serializeErSkjermet(body: ErSkjermetPersonBulkRequest) = TODO("Not yet implemented")
        }
    )
}