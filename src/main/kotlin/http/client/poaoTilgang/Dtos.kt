package no.nav.http.client.poaoTilgang

import kotlinx.serialization.Serializable
import no.nav.poao_tilgang.api.dto.request.PolicyId
import no.nav.poao_tilgang.api.dto.request.TilgangType
import no.nav.poao_tilgang.api.dto.response.DecisionType

@Serializable
class EvalPolicyReq(
    val requests: List<NavAnsattTilgangPolicyRequestDto>
)

@Serializable
class NavAnsattTilgangPolicyRequestDto(
    val requestId: String,
    val policyInput: Input,
    val policyId: PolicyId
)

@Serializable
sealed class Input

@Serializable
data class NavAnsattTilgangTilNavEnhetPolicyInput(
    val navAnsattAzureId: String,
    val navEnhetId: String
): Input()

@Serializable
data class NavAnsattTilgangTilBrukerPolicyInput(
    val navAnsattAzureId: String,
    val tilgangType: TilgangType,
    val norskIdent: String
): Input()

@Serializable
data class EvalPolicyRes(
    val results: List<PolicyEvaluationResultDto>
)

@Serializable
data class PolicyEvaluationResultDto(
    val requestId: String,
    val decision: DecisionDto
)

@Serializable
data class DecisionDto(val type: DecisionType, val message: String?, val reason: String?)