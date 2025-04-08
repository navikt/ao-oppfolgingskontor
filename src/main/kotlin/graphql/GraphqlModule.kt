package no.nav.graphql

import com.expediagroup.graphql.server.ktor.GraphQL
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.routing.Routing

class HelloWorldQuery : Query {
    fun hello(): String = "Hello World!"
}

fun Application.graphQlModule() {
    install(GraphQL) {
        schema {
            packages = listOf("com.example")
            queries = listOf(
                HelloWorldQuery()
            )
        }
    }
    install(Routing) {
        graphQLPostRoute()
    }
    install(StatusPages) {
        defaultGraphQLStatusPages()
    }
}
