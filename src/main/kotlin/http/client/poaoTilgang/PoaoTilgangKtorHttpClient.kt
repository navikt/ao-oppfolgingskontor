package no.nav.http.client.poaoTilgang

import arrow.core.Either
import io.ktor.client.*
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationEnvironment
import no.nav.AOPrincipal
import no.nav.NavAnsatt
import no.nav.SystemPrincipal
import no.nav.db.Ident
import no.nav.domain.KontorId
import no.nav.http.client.tokenexchange.SystemTokenPlugin
import no.nav.http.client.tokenexchange.TexasTokenResponse
import no.nav.poao_tilgang.api.dto.request.PolicyId
import no.nav.poao_tilgang.api.dto.request.TilgangType
import no.nav.poao_tilgang.api.dto.response.DecisionType
import org.slf4j.LoggerFactory
import java.util.UUID

fun ApplicationEnvironment.getPoaoTilgangScope(): String {
    return config.property("apis.poaoTilgang.scope").getString()
}

sealed class TilgangTilKontorResult
object HarTilgangTilKontor: TilgangTilKontorResult()
class HarIkkeTilgangTilKontor(val message: String) : TilgangTilKontorResult()
class TilgangTilKontorOppslagFeil(val message: String) : TilgangTilKontorResult()

sealed class TilgangTilBrukerResult
object HarTilgangTilBruker: TilgangTilBrukerResult()
class HarIkkeTilgangTilBruker(val message: String) : TilgangTilBrukerResult()
class TilgangTilBrukerOppslagFeil(val message: String) : TilgangTilBrukerResult()

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
            install(DefaultRequest) {
                accept(ContentType.Application.Json)
                contentType(ContentType.Application.Json)
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

    suspend fun harTilgangTilKontor(principal: AOPrincipal, kontorId: KontorId): TilgangTilKontorResult {
        return when (principal) {
            is NavAnsatt ->  harTilgangTilKontor(principal, kontorId)
            is SystemPrincipal -> HarTilgangTilKontor
        }
    }

    private suspend fun harTilgangTilKontor(navAnsatt: NavAnsatt, kontorId: KontorId): TilgangTilKontorResult {
        return evaluateNavAnsattHarTilgangTilKontorPolicy(evaluatePoliciesUrl, navAnsatt, kontorId)
    }

    private suspend fun harLeseTilgangTilBruker(navAnsatt: NavAnsatt, fnr: Ident): TilgangTilBrukerResult {
        return evaluateNavAnsattHarTilgangTilBrukerPolicy(evaluatePoliciesUrl, fnr, navAnsatt)
    }

    suspend fun harLeseTilgang(principal: AOPrincipal, fnr: Ident): TilgangTilBrukerResult {
        return when (principal) {
            is NavAnsatt ->  harLeseTilgangTilBruker(principal, fnr)
            is SystemPrincipal -> HarTilgangTilBruker
        }
    }

    private fun NavAnsatt.tilgangTilBrukerPayload(ident: Ident) = NavAnsattTilgangPolicyRequestDto(
        requestId = UUID.randomUUID().toString(),
        policyInput = NavAnsattTilgangTilBrukerPolicyInput(
            navAnsattAzureId = this.navAnsattAzureId.toString(),
            norskIdent = ident.value,
            tilgangType = TilgangType.LESE
        ),
        policyId = PolicyId.NAV_ANSATT_TILGANG_TIL_EKSTERN_BRUKER_V2
    )
    private fun NavAnsatt.tilgangTilKontorPayload(kontorId: KontorId) = NavAnsattTilgangPolicyRequestDto(
        requestId = UUID.randomUUID().toString(),
        policyInput = NavAnsattTilgangTilNavEnhetPolicyInput(
            navAnsattAzureId = this.navAnsattAzureId.toString(),
            navEnhetId = kontorId.id
        ),
        policyId = PolicyId.NAV_ANSATT_TILGANG_TIL_NAV_ENHET_V1
    )

    suspend fun evaluatePolicyOverHttp(fullUrl: String, policyPayload: NavAnsattTilgangPolicyRequestDto): Either<String, PolicyEvaluationResultDto> {
        return Either.catch {
            val response = client.post (fullUrl) {
                accept(ContentType.Application.Json)
                contentType(ContentType.Application.Json)
                setBody(EvalPolicyReq(listOf(policyPayload)))
            }
            if (response.status != HttpStatusCode.OK) {
                val message = "http request poao-tilgang evaluatePolicy ${policyPayload.policyId.name} failed with status ${response.status.value} - ${response.bodyAsText()}"
                return Either.Left(message)
            } else {
                val results = response.body<EvalPolicyRes>()
                    .results.firstOrNull() ?: return Either.Left("No results found for requestId ${policyPayload.requestId}")
                return Either.Right(results)
            }
        }
            .mapLeft {
                val message = "Http request til poao-tilgang feilet med exception: ${it.message}"
                log.error(message, it)
                return Either.Left(message)
            }
    }

    private suspend fun evaluateNavAnsattHarTilgangTilBrukerPolicy(
        fullUrl: String,
        fnr: Ident,
        navAnsatt: NavAnsatt
    ): TilgangTilBrukerResult {
        val policyPayload = navAnsatt.tilgangTilBrukerPayload(fnr)
        return evaluatePolicyOverHttp(fullUrl, policyPayload)
            .map { result ->
                if (result.decision.type == DecisionType.PERMIT) HarTilgangTilBruker
                else HarIkkeTilgangTilBruker("Har ikke tilgang: ${result.decision.message} - ${result.decision.reason}")
            }
            .mapLeft { errorMessage -> TilgangTilBrukerOppslagFeil(errorMessage) }
            .fold({ it }, { it })
    }

    private suspend fun evaluateNavAnsattHarTilgangTilKontorPolicy(
        fullUrl: String,
        navAnsatt: NavAnsatt,
        kontorId: KontorId
    ): TilgangTilKontorResult {
            val policyPayload = navAnsatt.tilgangTilKontorPayload(kontorId)
            return evaluatePolicyOverHttp(fullUrl, policyPayload)
                .map { result ->
                    if (result.decision.type == DecisionType.PERMIT) HarTilgangTilKontor
                    else HarIkkeTilgangTilKontor("Har ikke tilgang: ${result.decision.message} - ${result.decision.reason}")
                }
                .mapLeft { errorMessage -> TilgangTilKontorOppslagFeil(errorMessage) }
                .fold({ it }, { it })
    }
}