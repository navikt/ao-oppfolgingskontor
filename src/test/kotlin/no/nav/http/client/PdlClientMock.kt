package no.nav.http.client

import io.ktor.client.HttpClient
import io.ktor.client.plugins.logging.Logging
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder

const val pdlTestUrl = "http://pdl.test.local"

fun ApplicationTestBuilder.mockExternalService(block: Routing.() -> Unit): HttpClient {
    externalServices {
        hosts(pdlTestUrl) {
            routing {
                install(ContentNegotiation) {
                    json()
                }
                block()
            }
        }
    }
    val client = createClient {
        install(Logging)
        install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
            json()
        }

    }
    return client
}

fun ApplicationTestBuilder.mockPdl(graphqlResponse: String): HttpClient {
    return mockExternalService {
        post("/graphql") {
            call.respond(HttpStatusCode.OK, graphqlResponse)
        }
    }
}

fun ApplicationTestBuilder.mockPdl(httpStatusCode: HttpStatusCode): HttpClient {
    return mockExternalService {
        post("/graphql") {
            call.respond(httpStatusCode)
        }
    }
}