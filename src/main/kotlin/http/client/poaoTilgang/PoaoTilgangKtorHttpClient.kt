package no.nav.http.client.poaoTilgang

import io.ktor.client.*
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationEnvironment
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import no.nav.AOPrincipal
import no.nav.NavAnsatt
import no.nav.SystemPrincipal
import no.nav.db.Fnr
import no.nav.domain.KontorId
import no.nav.domain.NavIdent
import no.nav.http.client.tokenexchange.SystemTokenPlugin
import no.nav.http.client.tokenexchange.TexasTokenResponse
import no.nav.poao_tilgang.api.dto.request.ErSkjermetPersonBulkRequest
import no.nav.poao_tilgang.api.dto.request.EvaluatePoliciesRequest
import no.nav.poao_tilgang.api.dto.request.HentAdGrupperForBrukerRequest
import no.nav.poao_tilgang.api.dto.response.TilgangsattributterResponse
import no.nav.poao_tilgang.client_core.Decision
import no.nav.poao_tilgang.client_core.NavAnsattTilgangTilEksternBrukerPolicyInput
import no.nav.poao_tilgang.client_core.PoaoTilgangClient
import no.nav.poao_tilgang.client_core.PoaoTilgangHttpClient
import no.nav.poao_tilgang.client_core.TilgangType
import no.nav.poao_tilgang.client_core.api.ApiResult
import no.nav.poao_tilgang.client_core.api.ResponseDataApiException
import org.slf4j.LoggerFactory
import java.util.UUID

sealed class GTKontorResultat
data class GTKontorFunnet(val kontorId: KontorId) : GTKontorResultat()
data class GTKontorFeil(val melding: String) : GTKontorResultat()

fun ApplicationEnvironment.getPoaoTilgangScope(): String {
    return config.property("apis.poaoTilgang.scope").getString()
}

class PoaoTilgangKtorHttpClient(
    private val baseUrl: String,
    private val client: HttpClient
) {
    constructor(
        baseUrl: String,
        azureTokenProvider: suspend () -> TexasTokenResponse,
    ): this(
        baseUrl,
        HttpClient(CIO) {
            install(SystemTokenPlugin) {
                this.tokenProvider = azureTokenProvider
            }
            install(Logging) {
                level = LogLevel.INFO
            }
            install(ContentNegotiation) {
                json()
            }
        }
    )

    private val log = LoggerFactory.getLogger(javaClass)

    init {
        log.info("Starting HttpClient")
    }

    fun hentTilgangsattributter(ident: String) = poaoTilgangKtorHttpClient.hentTilgangsAttributter(ident)
        .let {
            when (it.isSuccess) {
                true -> it.get()?.kontor?.let { kontorId -> GTKontorFunnet(KontorId(kontorId)) }
                    ?: GTKontorFeil("Ingen kontor funnet for fnr: $ident")
                false -> GTKontorFeil("Feil ved henting av tilgangsattributter for fnr: $ident, ${it.exception?.message}")
            }
        }

    private fun harLeseTilgangTilBruker(navAnsatt: NavAnsatt, fnr: Fnr): ApiResult<Decision> {
        return poaoTilgangKtorHttpClient.evaluatePolicy(
            NavAnsattTilgangTilEksternBrukerPolicyInput(
                navAnsattAzureId = navAnsatt.navAnsattAzureId,
                norskIdent = fnr,
                tilgangType = TilgangType.LESE
            )
        )
    }

    fun harLeseTilgang(principal: AOPrincipal, fnr: Fnr): ApiResult<Decision> {
        return when (principal) {
            is NavAnsatt -> harLeseTilgangTilBruker(principal, fnr)
            is SystemPrincipal -> ApiResult.success(Decision.Permit)
        }
    }

    private fun fetch(
        fullUrl: String,
        method: String,
        body: String? = null
    ): ApiResult<String> {
        return runBlocking {
            log.info("Poaotilgang posting to $fullUrl - baseUrl: $baseUrl")
            val response = client.request (fullUrl) {
                this.method = HttpMethod.Post
                accept(ContentType.Application.Json)
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

    /* Lager en egen serializer fordi man ikke kan annotere en klasse fra et lib med @Serializable */
    val json = Json {
        serializersModule = SerializersModule {
            contextual(TilgangsattributterResponse::class, PoaoTilgangSerializer)
        }
    }

    private val poaoTilgangKtorHttpClient = PoaoTilgangHttpClient(
        baseUrl,
        httpFetch = ::fetch,
        bodyParser = object : PoaoTilgangClient.BodyParser {

            override fun parseHentTilgangsAttributterBody(body: String): ApiResult<TilgangsattributterResponse> {
                try {
                    return json.decodeFromString<TilgangsattributterResponse>(body).let { ApiResult.success(it) }
                } catch (e: Exception) {
                    log.error("Kunne ikke parse tilgangsattributter. Message: ${e.message}")
                    return ApiResult.failure(ResponseDataApiException("Kunne ikke parse tilgangsattributter. Message: ${e.message}"))
                }
            }

            override fun parsePolicyRequestsBody(body: String) = TODO("parsePolicyRequestsBody Not yet implemented")
            override fun parseErSkjermetPersonBody(body: String) = TODO("Not yet implemented")
            override fun parseHentAdGrupper(body: String) = TODO("Not yet implemented")
        },
        serializer = object : PoaoTilgangClient.Serializer {
            override fun <I> serializeEvaluatePolicies(body: EvaluatePoliciesRequest<I>): String {
                return json.encodeToString(body)
            }
            override fun serializeHentAdGrupper(body: HentAdGrupperForBrukerRequest) = TODO("Not yet implemented")
            override fun serializeErSkjermet(body: ErSkjermetPersonBulkRequest) = TODO("Not yet implemented")
        }
    )
}