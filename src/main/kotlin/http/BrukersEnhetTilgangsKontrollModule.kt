package http

import db.table.IdentMappingTable
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import no.nav.Authenticated
import no.nav.NavAnsatt
import no.nav.NotAuthenticated
import no.nav.SystemPrincipal
import no.nav.authenticateCall
import no.nav.db.AktorId
import no.nav.db.Ident
import no.nav.getIssuer
import no.nav.http.graphql.AuthenticateRequest
import org.slf4j.LoggerFactory

data class BrukerKontorBulkDto(
    val brukere: List<String>
)

fun Route.tilgangsEndepunkt(
    authenticateRequest: AuthenticateRequest = { req -> req.call.authenticateCall(environment.getIssuer()) }
) {
   val log = LoggerFactory.getLogger("Bruker.EnhetTilgangsKontroll")

    post("/api/brukerkontor-bulk") {
        val identer = call.receive<BrukerKontorBulkDto>().brukere
            .map { Ident.of(it) }
        val principal = when(val authresult = authenticateRequest(call.request)) {
            is Authenticated -> {
                when (authresult.principal) {
                    is NavAnsatt -> {
                        call.respond(HttpStatusCode.Unauthorized)
                        return@post
                    }
                    is SystemPrincipal -> authresult.principal
                }
            }
            is NotAuthenticated -> {
                log.warn("Not authorized ${authresult.reason}")
                call.respond(HttpStatusCode.Unauthorized)
                return@post
            }
        }

        val identerSomIkkeErForetrukketIdent = identer.filter { it is AktorId }
        IdentMappingTable

    }
}