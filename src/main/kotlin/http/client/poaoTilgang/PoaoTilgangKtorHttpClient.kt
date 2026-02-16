package no.nav.http.client.poaoTilgang

import io.ktor.client.*
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationEnvironment
import kotlinx.serialization.json.Json
import no.nav.AOPrincipal
import no.nav.NavAnsatt
import no.nav.SystemPrincipal
import no.nav.db.Fnr
import no.nav.db.Ident
import no.nav.http.client.tokenexchange.SystemTokenPlugin
import no.nav.http.client.tokenexchange.TexasTokenResponse
import no.nav.poao_tilgang.api.dto.request.TilgangType
import no.nav.poao_tilgang.api.dto.response.DecisionType
import org.slf4j.LoggerFactory
import java.util.UUID

fun ApplicationEnvironment.getPoaoTilgangScope(): String {
    return config.property("apis.poaoTilgang.scope").getString()
}

sealed class TilgangResult
sealed class HarTilgang: TilgangResult()
object SystemHarTilgang: HarTilgang()
class PersonHarTilgang(
    val subject: NavAnsatt,
    val target: Ident,
    val traceId: String,
): HarTilgang()


class HarIkkeTilgang(
    val subject: NavAnsatt,
    val target: Ident,
    val message: String,
    val traceId: String,
) : TilgangResult()
class TilgangOppslagFeil(val message: String) : TilgangResult()

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
    companion object {
        val evaluatePoliciesPath = "/api/v1/policy/evaluate"
    }
    val evaluatePoliciesUrl = "$baseUrl$evaluatePoliciesPath"

    /* Lager en egen serializer fordi man ikke kan annotere en klasse fra et lib med @Serializable */
    val json = Json {
//        serializersModule = SerializersModule {
//            contextual(TilgangsattributterResponse::class, PoaoTilgangSerializer)
//            contextual(EvaluatePoliciesRequest::class, PoaoTilgangSerializer)
//        }
    }

    private suspend fun harLeseTilgangTilBruker(navAnsatt: NavAnsatt, fnr: Ident, traceId: String): TilgangResult {
        return evaluatePolicy(evaluatePoliciesUrl, fnr, navAnsatt, traceId)
    }

    suspend fun harLeseTilgang(principal: AOPrincipal, fnr: Ident, traceId: String): TilgangResult {
        return when (principal) {
            is NavAnsatt ->  harLeseTilgangTilBruker(principal, fnr, traceId)
            is SystemPrincipal -> SystemHarTilgang
        }
    }

    private suspend fun evaluatePolicy(
        fullUrl: String,
        fnr: Ident,
        navAnsatt: NavAnsatt,
        traceId: String
    ): TilgangResult {
        return try {
            val requestId = UUID.randomUUID().toString()
            val response = client.post (fullUrl) {
                accept(ContentType.Application.Json)
                contentType(ContentType.Application.Json)
                setBody(EvalPolicyReq(
                        requests = listOf(
                            NavAnsattTilgangTilEksternBrukerPolicyRequestDto(
                                requestId = requestId,
                                policyInput = Input(
                                    navAnsattAzureId = navAnsatt.navAnsattAzureId.toString(),
                                    norskIdent = fnr.value,
                                    tilgangType = TilgangType.LESE
                                ),
                            )
                        )
                    )
                )
            }

            if (response.status != HttpStatusCode.OK) {
                log.error("http request poao-tilgang evaluatePolicy failed with status ${response.status.value} - ${response.bodyAsText()}")
                TilgangOppslagFeil("http request poao-tilgang evaluatePolicy failed with status ${response.status.value}")
            } else {
                val results = response.body<EvalPolicyRes>()
                val result = results.results.firstOrNull()
                    ?: return TilgangOppslagFeil("No results found for requestId $requestId")
                if (result.decision.type == DecisionType.PERMIT) {
                    PersonHarTilgang(
                        navAnsatt,
                        fnr,
                        traceId
                    )
                } else {
                    HarIkkeTilgang(
                        navAnsatt,
                        fnr,
                        "Har ikke tilgang: ${result.decision.message} - ${result.decision.reason}",
                        traceId
                    )
                }
            }
        } catch (e: Throwable) {
            val message = "Http request til poao-tilgang feilet med exception: ${e.message}"
            log.error(message, e)
            TilgangOppslagFeil(message)
        }
    }

    /*
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

            override fun parsePolicyRequestsBody(body: String) = TODO("Not yet implemented")
            override fun parseErSkjermetPersonBody(body: String) = TODO("Not yet implemented")
            override fun parseHentAdGrupper(body: String) = TODO("Not yet implemented")
        },
        serializer = object : PoaoTilgangClient.Serializer {
            override fun serializeEvaluatePolicies(body: EvaluatePoliciesRequest): String {
                return json.encodeToString(EvaluatePoliciesRequestSerializer, body)
            }
            override fun serializeHentAdGrupper(body: HentAdGrupperForBrukerRequest) = TODO("Not yet implemented")
            override fun serializeErSkjermet(body: ErSkjermetPersonBulkRequest) = TODO("Not yet implemented")
        }
    )*/
}