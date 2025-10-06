package http

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import no.nav.*
import no.nav.http.graphql.AuthenticateRequest
import no.nav.services.KontorTilhorighetService

fun Application.hentArbeidsoppfolgingskontorModule(
    kontorTilhorighetService: KontorTilhorighetService,
    authenticateRequest: AuthenticateRequest = { req -> req.call.authenticateCall(environment.getIssuer()) }
) {
    fun RoutingCall.erSystembruker(): Boolean {
        val principal = when (val authresult = authenticateRequest(this.request)) {
            is Authenticated -> authresult.principal
            is NotAuthenticated -> {
                log.warn("Not authorized ${authresult.reason}")
                return false
            }
        }
        return when (principal) {
            is NavAnsatt -> false
            is SystemPrincipal -> true
        }
    }

    routing {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            })
        }
        authenticate("EntraAD") {
            post("api/tilgang/brukers-kontor-bulk") {
                if (!call.erSystembruker()) {
                    call.respond(HttpStatusCode.Forbidden, "Bare systembrukere kan bruke /brukers-kontor-bulk endepunkt")
                    return@post
                }
            }
        }
    }
}