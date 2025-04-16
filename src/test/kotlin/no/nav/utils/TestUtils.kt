package no.nav.utils

import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*

fun ApplicationTestBuilder.getJsonClient(): HttpClient {
    return createClient {
        install(ContentNegotiation) { json() }
        install(Logging)
    }
}
