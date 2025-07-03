package no.nav.http.graphql

import com.expediagroup.graphql.generator.extensions.toGraphQLContext
import com.expediagroup.graphql.server.execution.GraphQLContextFactory
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
import io.ktor.server.auth.authentication
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.ApplicationRequest
import io.ktor.server.routing.routing
import no.nav.authenticate
import no.nav.http.client.Norg2Client
import no.nav.http.graphql.queries.AlleKontorQuery
import no.nav.http.graphql.queries.KontorHistorikkQuery
import no.nav.http.graphql.queries.KontorQuery
import no.nav.services.KontorTilhorighetService

class AppContextFactory(val issuer: String) : KtorGraphQLContextFactory() {
    override suspend fun generateContext(request: ApplicationRequest): GraphQLContext {
        val principal = request.call.authenticate(issuer)
        return mapOf(
            "principal" to principal,
        ).toGraphQLContext()
    }
}

fun Application.installGraphQl(norg2Client: Norg2Client, kontorTilhorighetService: KontorTilhorighetService, issuer: String) {
    install(GraphQL) {
        schema {
            packages = listOf(
                "no.nav.http.graphql.schemas",
                "no.nav.http.graphql.queries",
            )
            queries = listOf(
                KontorQuery(kontorTilhorighetService),
                KontorHistorikkQuery(),
                AlleKontorQuery(norg2Client)
            )
        }
        server {
            contextFactory = AppContextFactory(issuer)
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

fun Application.configureGraphQlModule(norg2Client: Norg2Client, kontorTilhorighetService: KontorTilhorighetService, issuer: String) {
    installGraphQl(norg2Client, kontorTilhorighetService, issuer)

    routing {
        authenticate {
            graphQLPostRoute()
        }
        graphQLSDLRoute()
        graphiQLRoute()
    }

    install(StatusPages) {
        defaultGraphQLStatusPages()
    }
}
