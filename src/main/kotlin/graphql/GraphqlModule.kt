package no.nav.graphql

import com.expediagroup.graphql.server.ktor.GraphQL
import com.expediagroup.graphql.server.ktor.graphQLPostRoute
import com.expediagroup.graphql.server.ktor.defaultGraphQLStatusPages
import com.expediagroup.graphql.server.ktor.graphQLSDLRoute
import com.expediagroup.graphql.server.ktor.graphiQLRoute
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.routing
import no.nav.graphql.queries.KontorHistorikkQuery
import no.nav.graphql.queries.KontorQuery

fun Application.graphQlModule() {
    install(GraphQL) {
        schema {
            packages = listOf(
                "no.nav.graphql.schemas",
                "no.nav.graphql.queries",
            )
            queries = listOf(
                KontorQuery(),
                KontorHistorikkQuery()
            )
        }
    }

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
