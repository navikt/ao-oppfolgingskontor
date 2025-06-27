package http.client

import com.expediagroup.graphql.client.types.GraphQLClientResponse
import io.kotest.matchers.shouldBe
import io.ktor.client.plugins.logging.Logging
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import no.nav.http.client.GtForBrukerFunnet
import no.nav.http.client.GtForBrukerIkkeFunnet
import no.nav.http.client.PdlClient
import no.nav.http.client.toGeografiskTilknytning
import no.nav.http.graphql.generated.client.HentGtQuery
import no.nav.http.graphql.generated.client.enums.GtType
import no.nav.http.graphql.generated.client.hentgtquery.GeografiskTilknytning
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

    @Test
    fun `skal håndtere feil i graphql reponse på spørring på GT`() = testApplication {
        val fnr = "12345678901"
        val pdlTestUrl = "http://pdl.test.local"
        val errorMessage = "Ingen GT funnet for bruker"
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
                                "data": null,
                                "errors": [{
                                    "message": "$errorMessage",
                                    "extensions": {
                                        "code": "NOT_FOUND"
                                    }
                                }]
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
        (gt is GtForBrukerIkkeFunnet) shouldBe true
        // Also picks some more details from the response but didnt bother mocking that
        (gt as GtForBrukerIkkeFunnet).message shouldBe "${errorMessage}: null"
    }

    @Test
    fun `skal håndtere http-feil ved graphql spørring på GT`() = testApplication {
        val fnr = "12345678901"
        val pdlTestUrl = "http://pdl.test.local"
        externalServices {
            hosts(pdlTestUrl) {
                routing {
                    install(ContentNegotiation) {
                        json()
                    }
                    post("/graphql") {
                        call.respond(HttpStatusCode.InternalServerError)
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
        (gt is GtForBrukerIkkeFunnet) shouldBe true
        // Also picks some more details from the response but didnt bother mocking that
        (gt as GtForBrukerIkkeFunnet).message shouldBe """
            Henting av GT for bruker feilet: Server error(POST http://pdl.test.local/graphql: 500 Internal Server Error. Text: ""
        """.trimIndent()
    }

    @Test
    fun `skal plukke riktig gt`() {
        val bydelResponse = response(GtType.BYDEL, gtBydel = "3333")
        bydelResponse.toGeografiskTilknytning() shouldBe GtForBrukerFunnet(no.nav.http.client.GeografiskTilknytning("3333"))

        val kommuneResponse = response(GtType.KOMMUNE, gtKommune = "4444")
        kommuneResponse.toGeografiskTilknytning() shouldBe GtForBrukerFunnet(no.nav.http.client.GeografiskTilknytning("4444"))

        val landResponse = response(GtType.UTLAND, gtLand = "SVERIGE")
        landResponse.toGeografiskTilknytning() shouldBe GtForBrukerFunnet(no.nav.http.client.GeografiskTilknytning("SVERIGE"))

        val feilResponse = response(gtType = GtType.UTLAND)
        feilResponse.toGeografiskTilknytning() shouldBe GtForBrukerIkkeFunnet("Ingen gyldige verider i GT repons fra PDL funnet for type UTLAND bydel: null, kommune: null, land: null")
    }

    fun response(
        gtType: GtType,
        gtKommune: String? = null,
        gtBydel: String? = null,
        gtLand: String? = null
    ): GraphQLClientResponse<HentGtQuery.Result> {
        return object : GraphQLClientResponse<HentGtQuery.Result> {
            override val data = HentGtQuery.Result(
                GeografiskTilknytning(
                    gtType = gtType,
                    gtKommune = gtKommune,
                    gtBydel = gtBydel,
                    gtLand = gtLand
                )
            )
            override val errors = null
        }
    }
}