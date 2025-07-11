package no.nav.http.client.poaoTilgang

import kotlinx.serialization.Serializable
import no.nav.poao_tilgang.api.dto.request.PolicyId
import no.nav.poao_tilgang.api.dto.request.TilgangType
import no.nav.poao_tilgang.api.dto.response.DecisionType

@Serializable
class EvalPolicyReq(
    val requests: List<NavAnsattTilgangTilEksternBrukerPolicyRequestDto>
)

@Serializable
class NavAnsattTilgangTilEksternBrukerPolicyRequestDto(
    val requestId: String,
    val policyInput: Input,
    val policyId: PolicyId = PolicyId.NAV_ANSATT_TILGANG_TIL_EKSTERN_BRUKER_V2
)

@Serializable
data class Input(
    val navAnsattAzureId: String,
    val tilgangType: TilgangType,
    val norskIdent: String
)

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