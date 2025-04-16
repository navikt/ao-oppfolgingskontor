package no.nav.http.graphql

import com.expediagroup.graphql.server.ktor.GraphQL
import com.expediagroup.graphql.server.ktor.graphQLPostRoute
import com.expediagroup.graphql.server.ktor.defaultGraphQLStatusPages
import com.expediagroup.graphql.server.ktor.graphQLSDLRoute
import com.expediagroup.graphql.server.ktor.graphiQLRoute
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationEnvironment
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.routing
import no.nav.http.client.Norg2Client
import no.nav.http.graphql.queries.AlleKontorQuery
import no.nav.http.graphql.queries.KontorHistorikkQuery
import no.nav.http.graphql.queries.KontorQuery

fun Application.installGraphQl(norg2Client: Norg2Client) {
    install(GraphQL) {
        schema {
            packages = listOf(
                "no.nav.http.graphql.schemas",
                "no.nav.http.graphql.queries",
            )
            queries = listOf(
                KontorQuery(),
                KontorHistorikkQuery(),
                AlleKontorQuery(norg2Client)
            )
        }
    }
}

fun ApplicationEnvironment.getNorg2Url(): String {
    return config.property("apis.norg2.url").getString()
}

fun Application.configureGraphQlModule() {
    val norg2Client = Norg2Client(environment.getNorg2Url())

    installGraphQl(norg2Client)

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
