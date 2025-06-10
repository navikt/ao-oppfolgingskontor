package no.nav.http.client

import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import no.nav.poao_tilgang.api.dto.response.Diskresjonskode
import no.nav.poao_tilgang.api.dto.response.TilgangsattributterResponse

val poaoTilgangTestUrl = "https://poao-tilgang.test.no"

fun ApplicationTestBuilder.mockPoaoTilgangHost(kontorId: String?): PoaoTilgangKtorHttpClient {
    externalServices {
        hosts(poaoTilgangTestUrl) {
            routing {
                install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) {
                    json()
                }

                post("/api/v1/tilgangsattributter") {
                    call.respond(TilgangsattributterResponse(
                        kontor = kontorId,
                        skjermet = false,
                        diskresjonskode = Diskresjonskode.UGRADERT
                    ))
                }
            }

        }
    }
    return PoaoTilgangKtorHttpClient(
        createClient {
            install(ContentNegotiation) { json() }
            install(Logging)
            defaultRequest {
                url(poaoTilgangTestUrl)
            }
        }
    )
}