package no.nav.http.graphql

import com.expediagroup.graphql.generator.extensions.toGraphQLContext
import com.expediagroup.graphql.server.ktor.GraphQL
import com.expediagroup.graphql.server.ktor.KtorGraphQLContextFactory
import com.expediagroup.graphql.server.ktor.graphQLPostRoute
import com.expediagroup.graphql.server.ktor.defaultGraphQLStatusPages
import com.expediagroup.graphql.server.ktor.graphQLSDLRoute
import com.expediagroup.graphql.server.ktor.graphiQLRoute
import graphql.GraphQLContext
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationEnvironment
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.ApplicationRequest
import io.ktor.server.routing.routing
import no.nav.AOPrincipal
import no.nav.AuthResult
import no.nav.Authenticated
import no.nav.NotAuthenticated
import no.nav.db.Ident
import no.nav.http.client.IdenterResult
import no.nav.http.client.Norg2Client
import no.nav.http.client.poaoTilgang.TilgangResult
import no.nav.http.graphql.queries.AlleKontorQuery
import no.nav.http.graphql.queries.KontorHistorikkQuery
import no.nav.http.graphql.queries.KontorQuery
import no.nav.services.KontorTilhorighetService
import org.slf4j.LoggerFactory

typealias AuthenticateRequest = (request: ApplicationRequest) -> AuthResult

class AppContextFactory(val authenticateRequest: AuthenticateRequest) : KtorGraphQLContextFactory() {
    val log = LoggerFactory.getLogger(AppContextFactory::class.java)
    override suspend fun generateContext(request: ApplicationRequest): GraphQLContext {
        val authResult = authenticateRequest(request)
        val principal = when (authResult) {
            is Authenticated -> authResult.principal
            is NotAuthenticated -> {
                log.warn("Not authenticated: ${authResult.reason}")
                request.headers.get("Authorization")?.let { authHeader ->
                    log.warn("Request with Authorization header: $authHeader")
                }
                throw IllegalStateException("Failed to authenticate graphql request: ${authResult.reason}")
            }
        }
        return mapOf(
            "principal" to principal,
        ).toGraphQLContext()
    }
}

fun Application.installGraphQl(
    norg2Client: Norg2Client,
    kontorTilhorighetService: KontorTilhorighetService,
    authenticateRequest: AuthenticateRequest,
    hentAlleIdenter: suspend (Ident) -> IdenterResult,
    harLeseTilgang: suspend (AOPrincipal, Ident) -> TilgangResult,
) {
    install(GraphQL) {
        schema {
            packages = listOf(
                "no.nav.http.graphql.schemas",
                "no.nav.http.graphql.queries",
            )
            queries = listOf(
                KontorQuery(kontorTilhorighetService, { principal, ident -> harLeseTilgang(principal, ident) }, hentAlleIdenter = hentAlleIdenter),
                KontorHistorikkQuery(hentAlleIdenter),
                AlleKontorQuery(norg2Client)
            )
            federation {
                tracing {
                    enabled = true
                }
            }
        }
        server {
            contextFactory = AppContextFactory(authenticateRequest)
        }
    }
}

fun ApplicationEnvironment.getNorg2Url(): String {
    return config.property("apis.norg2.url").getString()
}

fun ApplicationEnvironment.getPoaoTilgangUrl(): String {
    return config.property("apis.poaoTilgang.url").getString()
}

fun ApplicationEnvironment.getPDLUrl(): String {
    return config.property("apis.pdl.url").getString()
}

fun ApplicationEnvironment.getVeilarbArenaUrl(): String {
    return config.property("apis.veilarbarena.url").getString()
}

fun ApplicationEnvironment.getAaregUrl(): String {
    return config.property("apis.aareg.url").getString()
}

fun ApplicationEnvironment.getEregUrl(): String {
    return config.property("apis.ereg.url").getString()
}

fun Application.configureGraphQlModule(
    norg2Client: Norg2Client,
    kontorTilhorighetService: KontorTilhorighetService,
    authenticateCall: AuthenticateRequest,
    hentAlleIdenter: suspend (Ident) -> IdenterResult,
    harLeseTilgang: suspend (AOPrincipal, Ident) -> TilgangResult) {
    installGraphQl(
        norg2Client,
        kontorTilhorighetService,
        authenticateCall,
        hentAlleIdenter,
        harLeseTilgang,
    )

    routing {
        authenticate("EntraAD") {
            graphQLPostRoute()
        }
        graphQLSDLRoute()
        graphiQLRoute()
    }

    install(StatusPages) {
        defaultGraphQLStatusPages()
    }
}
