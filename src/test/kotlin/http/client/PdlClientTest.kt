package http.client

import io.kotest.matchers.shouldBe
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import no.nav.http.client.GtForBrukerFunnet
import no.nav.http.client.PdlClient
import org.junit.Test

class PdlClientTest {

    @Test
    fun `skal plukke ut riktig gt fra PDL response`() = testApplication {
        val fnr = "12345678901"
        val pdlTestUrl = "http://pdl.test.local"
        val bydelGtNr = "4141"
        externalServices {
            hosts(pdlTestUrl) {
                routing {
                    install(ContentNegotiation) {
                        json()
                    }
                    post("/graphql") {
                        call.respond(
                            """
                            {
                                "data": {
                                    "hentGeografiskTilknytning": {
                                        "gtType": "BYDEL",
                                        "gtKommune": null,
                                        "gtBydel": "$bydelGtNr",
                                        "gtLand": null
                                    }
                                }
                            }
                            """.trimIndent()
                        )
                    }
                }
            }
        }
        val client = createClient {
            install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
                json()
            }
            install(Logging)
        }
        val pdlClient = PdlClient(pdlTestUrl,client)
        val gt = pdlClient.hentGt(fnr)
        (gt is GtForBrukerFunnet) shouldBe true
        (gt as GtForBrukerFunnet).gt.value shouldBe bydelGtNr
    }
}