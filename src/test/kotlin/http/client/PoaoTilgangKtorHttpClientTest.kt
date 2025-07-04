package http.client;

import io.kotest.matchers.shouldBe
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.testing.testApplication
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import no.nav.http.client.poaoTilgang.PoaoTilgangKtorHttpClient
import no.nav.http.client.poaoTilgang.PoaoTilgangSerializer
import no.nav.poao_tilgang.api.dto.response.Diskresjonskode
import no.nav.poao_tilgang.api.dto.response.TilgangsattributterResponse
import no.nav.services.GTKontorFunnet
import org.junit.jupiter.api.Test

class PoaoTilgangKtorHttpClientTest {

    @Test
    fun `skal poste til riktig url`() = testApplication {
        val url = "http://poao-tilgang-api.test"
        externalServices {
            hosts(url) {
                routing {
                    install(ContentNegotiation) {
                        json(
                            Json {
                                serializersModule = SerializersModule {
                                    contextual(TilgangsattributterResponse::class, PoaoTilgangSerializer)
                                }
                            }
                        )
                    }
                    post("/api/v1/tilgangsattributter") {
                        call.respond(
                            TilgangsattributterResponse(
                                "1234",
                                false,
                                Diskresjonskode.UGRADERT
                            )
                        )
                    }
                }
            }
        }

        val testClient = createClient {
            install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
                json()
            }
            install(Logging)
        }

        val client = PoaoTilgangKtorHttpClient(url, testClient)
        val reponse = client.hentTilgangsattributter("1234")

        (reponse is GTKontorFunnet) shouldBe true
    }

}
