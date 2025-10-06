package http

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import no.nav.services.KontorTilhorighetService

fun Application.hentArbeidsoppfolgingskontorModule(
    kontorTilhorighetService: KontorTilhorighetService
) {
    routing {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            })
        }
        authenticate("Systembruker") {
            post("api/kontor/hent") {

            }
        }
    }

}