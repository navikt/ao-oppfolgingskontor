package no.nav.http.client

import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.install
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import no.nav.http.client.poaoTilgang.DecisionDto
import no.nav.http.client.poaoTilgang.EvalPolicyRes
import no.nav.http.client.poaoTilgang.PoaoTilgangKtorHttpClient
import no.nav.http.client.poaoTilgang.PoaoTilgangKtorHttpClient.Companion.evaluatePoliciesPath
import no.nav.http.client.poaoTilgang.PolicyEvaluationResultDto
import no.nav.poao_tilgang.api.dto.response.DecisionType
import no.nav.poao_tilgang.api.dto.response.Diskresjonskode
import no.nav.poao_tilgang.api.dto.response.TilgangsattributterResponse
import java.util.UUID

val poaoTilgangTestUrl = "http://poao.tilgang.test.no"
private const val tilgangsattributterPath = "/api/v1/tilgangsattributter"

fun ApplicationTestBuilder.mockPoaoTilgangHost(kontorId: String?, harTilgang: Boolean = true): PoaoTilgangKtorHttpClient {
    externalServices {
        hosts(poaoTilgangTestUrl) {
            this.install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) {
                json()
            }
            routing {

                post(tilgangsattributterPath) {
                    call.respond(
                        TilgangsattributterResponse(
                            kontor = kontorId,
                            skjermet = false,
                            diskresjonskode = Diskresjonskode.UGRADERT
                        )
                    )
                }

                post(evaluatePoliciesPath) {
                    call.respond(EvalPolicyRes(
                        results = listOf(
                            PolicyEvaluationResultDto(
                                requestId = UUID.randomUUID().toString(),
                                decision = DecisionDto(
                                    type = if (harTilgang) DecisionType.PERMIT else DecisionType.DENY,
                                    reason = null,
                                    message = null
                                )
                            )
                        )
                    ))
                }
            }

        }
    }
    return PoaoTilgangKtorHttpClient(
        poaoTilgangTestUrl,
        createClient {
            install(ContentNegotiation) { json() }
            install(Logging)
            install(HttpTimeout) {
                requestTimeoutMillis = 10000
            }
            defaultRequest {
                url(poaoTilgangTestUrl)
            }
        }
    )


}