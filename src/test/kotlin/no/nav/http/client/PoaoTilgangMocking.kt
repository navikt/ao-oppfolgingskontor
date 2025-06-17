package no.nav.http.client

import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.install
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import no.nav.http.client.tokenexchange.TexasTokenSuccessResult
import no.nav.poao_tilgang.api.dto.response.Diskresjonskode
import no.nav.poao_tilgang.api.dto.response.TilgangsattributterResponse

val poaoTilgangTestUrl = "http://poao.tilgang.test.no"
private const val tilgangsattributterPath = "/api/v1/tilgangsattributter"

fun ApplicationTestBuilder.mockPoaoTilgangHost(kontorId: String?): PoaoTilgangKtorHttpClient {
    externalServices {
        hosts(poaoTilgangTestUrl) {
            this.install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) {
                json()
            }
            routing {

                post(tilgangsattributterPath) {
                    call.respond(
                        TilgangsattributterResponse(
                            kontor = kontorId,
                            skjermet = false,
                            diskresjonskode = Diskresjonskode.UGRADERT
                        )
                    )
                }
            }

        }
    }
    return PoaoTilgangKtorHttpClient(poaoTilgangTestUrl,
        createClient {
            install(ContentNegotiation) { json() }
            install(Logging)
            install(HttpTimeout) {
                requestTimeoutMillis = 10000
            }
            defaultRequest {
                url(poaoTilgangTestUrl)
            }
        }
    )


}