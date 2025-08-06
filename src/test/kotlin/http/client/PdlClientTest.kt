package http.client

import com.expediagroup.graphql.client.types.GraphQLClientResponse
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBePositive
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.http.*
import io.ktor.server.testing.*
import no.nav.db.Dnr
import no.nav.db.Fnr
import no.nav.db.Npid
import no.nav.http.client.*
import no.nav.http.graphql.generated.client.HentGtQuery
import no.nav.http.graphql.generated.client.enums.GtType
import no.nav.http.graphql.generated.client.enums.IdentGruppe
import no.nav.http.graphql.generated.client.hentfnrquery.IdentInformasjon
import no.nav.http.graphql.generated.client.hentgtquery.GeografiskTilknytning
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.Period
import java.time.ZonedDateTime

class PdlClientTest {

    @Test
    fun `hentGt skal plukke ut riktig gt fra PDL response`() = testApplication {
        val fnr = Fnr("12345678901")
        val bydelGtNr = "4141"
        val client = mockPdl(
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
        val pdlClient = PdlClient(pdlTestUrl, client)
        val gt = pdlClient.hentGt(fnr)
        gt.shouldBeInstanceOf<GtNummerForBrukerFunnet>()
        gt.gtNr.value shouldBe bydelGtNr
    }

    @Test
    fun `hentGt skal håndtere feil i graphql reponse på spørring på GT`() = testApplication {
        val fnr = Fnr("12345678901")
        val pdlTestUrl = "http://pdl.test.local"
        val errorMessage = "Ingen GT funnet for bruker"

        val client = mockPdl(
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
        val pdlClient = PdlClient(pdlTestUrl, client)
        val gt = pdlClient.hentGt(fnr)
        gt.shouldBeInstanceOf<GtForBrukerOppslagFeil>()
        gt.message shouldBe "${errorMessage}: null"
    }

    @Test
    fun `hentGt skal håndtere http-feil ved graphql spørring på GT`() = testApplication {
        val fnr = Fnr("12345678901")
        val client = mockPdl(HttpStatusCode.InternalServerError)
        val pdlClient = PdlClient(pdlTestUrl, client)
        val gt = pdlClient.hentGt(fnr)
        gt.shouldBeInstanceOf<GtForBrukerOppslagFeil>()
        gt.message shouldBe """
            Henting av GT for bruker feilet: Server error(POST http://pdl.test.local/graphql: 500 Internal Server Error. Text: ""
        """.trimIndent()
    }

    @Test
    fun `hentAlder skal returnere alder som et positivt heltall`() = testApplication {
        val fnr = Fnr("12345678901")
        val localDate = LocalDate.of(1990, 1, 31)
        val diff = Period.between(localDate, ZonedDateTime.now().toLocalDate()).years
        val client = mockPdl(
            hentPersonQuery(
                """
            {
                "foedselsdato": [{
                    "foedselsdato": "${localDate}"
                    "foedselsaar": ${localDate.year}
                }]
            }
        """.trimIndent()
            )
        )
        val pdlClient = PdlClient(pdlTestUrl, client)
        val alder = pdlClient.hentAlder(fnr)
        alder.shouldBeInstanceOf<AlderFunnet>()
        alder.alder.shouldBePositive()
        alder.alder shouldBe diff
    }

    @Test
    fun `hentAlder skal bruker foedselsaar til å beregne alder om foedselsdato ikke finnes`() = testApplication {
        val fnr = Fnr("12345678901")
        val localDate = LocalDate.of(1990, 1, 1)
        val now = LocalDate.of(2025, 12, 31)
        val diff = Period.between(localDate, now).years
        val client = mockPdl(
            hentPersonQuery(
                """
            {
                "foedselsdato": [{
                    "foedselsdato": null,
                    "foedselsaar": ${localDate.year}
                }]
            }
        """.trimIndent()
            )
        )
        val pdlClient = PdlClient(pdlTestUrl, client)
        val alder = pdlClient.hentAlder(fnr)
        alder.shouldBeInstanceOf<AlderFunnet>()
        alder.alder.shouldBePositive()
        alder.alder shouldBe diff
    }

    @Test
    fun `harStrengtFortroligAdresse skal returnere strengt fortrolig adresse true når adressebeskyttelse er STRENGT_FORTROLIG_UTLAND`() =
        testApplication {
            val fnr = Fnr("12345678901")
            val client = mockPdl(
                hentPersonQuery(
                    """
            {
                "adressebeskyttelse": [{
                    "gradering": "STRENGT_FORTROLIG_UTLAND"
                }]
            }
        """.trimIndent()
                )
            )
            val pdlClient = PdlClient(pdlTestUrl, client)

            val alder = pdlClient.harStrengtFortroligAdresse(fnr)

            alder.shouldBeInstanceOf<HarStrengtFortroligAdresseFunnet>()
            alder.harStrengtFortroligAdresse.value shouldBe true
        }

    @Test
    fun `harStrengtFortroligAdresse skal returnere strengt fortrolig adresse true når adressebeskyttelse er STRENGT_FORTROLIG`() =
        testApplication {
            val fnr = Fnr("12345678901")
            val client = mockPdl(
                hentPersonQuery(
                    """
            { "adressebeskyttelse": [{"gradering": "STRENGT_FORTROLIG" }] }
        """.trimIndent()
                )
            )
            val pdlClient = PdlClient(pdlTestUrl, client)

            val adressebeskyttelse = pdlClient.harStrengtFortroligAdresse(fnr)

            adressebeskyttelse.shouldBeInstanceOf<HarStrengtFortroligAdresseFunnet>()
            adressebeskyttelse.harStrengtFortroligAdresse.value shouldBe true
        }

    @Test
    fun `harStrengtFortroligAdresse skal returnere at bruker ikke har adressebeskyttelse når gradering feltet er null`() =
        testApplication {
            val fnr = Fnr("12345678901")
            val client = mockPdl(
                hentPersonQuery(
                    """
            { "adressebeskyttelse": [{ "gradering": null }] }
        """.trimIndent()
                )
            )
            val pdlClient = PdlClient(pdlTestUrl, client)

            val adressebeskyttelse = pdlClient.harStrengtFortroligAdresse(fnr)

            adressebeskyttelse.shouldBeInstanceOf<HarStrengtFortroligAdresseFunnet>()
            adressebeskyttelse.harStrengtFortroligAdresse.value shouldBe false
        }

    @Test
    fun `harStrengtFortroligAdresse skal returnere at bruker ikke har adressebeskyttelse når adressebeskyttelse er en tom liste`() =
        testApplication {
            val fnr = Fnr("12345678901")
            val client = mockPdl(
                hentPersonQuery(
                    """
            { "adressebeskyttelse": []}
        """.trimIndent()
                )
            )
            val pdlClient = PdlClient(pdlTestUrl, client)

            val adressebeskyttelse = pdlClient.harStrengtFortroligAdresse(fnr)

            adressebeskyttelse.shouldBeInstanceOf<HarStrengtFortroligAdresseFunnet>()
            adressebeskyttelse.harStrengtFortroligAdresse.value shouldBe false
        }

    @Test
    fun `harStrengtFortroligAdresse skal returnere feil oppslag ved ukjent felter`() = testApplication {
        val fnr = Fnr("12345678901")
        val client = mockPdl(
            hentPersonQuery(
                """
            { "ukjent_felt": [{"gradering": "STRENGT_FORTROLIG"}]}
        """.trimIndent()
            )
        )
        val pdlClient = PdlClient(pdlTestUrl, client)

        val adressebeskyttelse = pdlClient.harStrengtFortroligAdresse(fnr)

        adressebeskyttelse.shouldBeInstanceOf<HarStrengtFortroligAdresseOppslagFeil>()
    }

    @Test
    fun `hentFnrFraAktorId skal returnere fnr for aktorId`() = testApplication {
        val aktorId = "12345678901"
        val fnr = Fnr("12345678901")
        val client = mockPdl(
            """
            {
                "data": { 
                    "hentIdenter": {
                        "identer": [
                          {
                            "ident": "44444444",
                            "historisk": false,
                            "gruppe": "${IdentGruppe.NPID}"
                          },
                          {
                            "ident": "55555555",
                            "historisk": false,
                            "gruppe": "${IdentGruppe.AKTORID}"
                          },
                          {
                            "ident": "$fnr",
                            "historisk": false,
                            "gruppe": "${IdentGruppe.FOLKEREGISTERIDENT}"
                          }
                        ]
                    }
                },
                "errors": null
            }
        """.trimIndent()
        )
        val pdlClient = PdlClient(pdlTestUrl, client)

        val fnrResult = pdlClient.hentFnrFraAktorId(aktorId)

        fnrResult.shouldBeInstanceOf<IdenterFunnet>()
        fnrResult.identer shouldHaveSize 3
    }

    @Test
    fun `hentFnrFraAktorId skal returnere npid for aktorId hvis ikke fnr finnes`() = testApplication {
        val aktorId = "12345678901"
        val npid = Npid("41254141414")
        val client = mockPdl(
            """
            {
                "data": { 
                    "hentIdenter": {
                        "identer": [
                          {
                            "ident": "${npid}",
                            "historisk": false,
                            "gruppe": "${IdentGruppe.NPID}"
                          },
                          {
                            "ident": "5555555555555",
                            "historisk": false,
                            "gruppe": "${IdentGruppe.AKTORID}"
                          }
                        ]
                    }
                },
                "errors": null
            }
        """.trimIndent()
        )
        val pdlClient = PdlClient(pdlTestUrl, client)

        val fnrResult = pdlClient.hentFnrFraAktorId(aktorId)

        fnrResult.shouldBeInstanceOf<IdenterFunnet>()
        val ident = fnrResult.finnForetrukketIdent()
        ident.shouldBeInstanceOf<IdentFunnet>()
        ident.ident.shouldBeInstanceOf<Npid>()
        ident.ident shouldBe npid
    }

    @Test
    fun `hentFnrFraAktorId skal extension sin code i feilmelding`() = testApplication {
        val aktorId = "12345678901"
        val client = mockPdl(
            """
            {
                "data": null,
                "errors": [
                    {
                        "message": "Fant ikke person",
                        "locations": [],
                        "path": [],
                        "extensions": {
                          "code": "not_found",
                          "details": null,
                          "classification": "ExecutionAborted"
                        }
                  }
                ]
            }
        """.trimIndent()
        )
        val pdlClient = PdlClient(pdlTestUrl, client)

        val fnrResult = pdlClient.hentFnrFraAktorId(aktorId)

        fnrResult.shouldBeInstanceOf<IdenterOppslagFeil>()
        fnrResult.message shouldBe "Fant ikke person: not_found"
    }

    @Test
    fun `toGeografiskTilknytning skal plukke riktig gt`() {
        val bydelResponse = response(GtType.BYDEL, gtBydel = "3333")
        bydelResponse.toGeografiskTilknytning() shouldBe GtNummerForBrukerFunnet(GeografiskTilknytningBydelNr("3333"))

        val kommuneResponse = response(GtType.KOMMUNE, gtKommune = "4444")
        kommuneResponse.toGeografiskTilknytning() shouldBe GtNummerForBrukerFunnet(GeografiskTilknytningKommuneNr("4444"))

        val landResponse = response(GtType.UTLAND, gtLand = "SVERIGE")
        landResponse.toGeografiskTilknytning() shouldBe GtLandForBrukerFunnet(GeografiskTilknytningLand("SVERIGE"))

        val feilResponse = response(gtType = GtType.UTLAND)
        feilResponse.toGeografiskTilknytning() shouldBe GtForBrukerIkkeFunnet("Ingen gyldige verider i GT repons fra PDL funnet for type UTLAND bydel: null, kommune: null, land: null")
    }

    @Test
    fun `gjeldende fnr og dnr skal foretrekke fnr`() {
        val fnr = Fnr("22122222222")
        val dnr = Dnr("55125555555")

        val foretrukketIdent = IdenterFunnet(
            listOf(
                IdentInformasjon(dnr.value, false, IdentGruppe.FOLKEREGISTERIDENT),
                IdentInformasjon(fnr.value, false, IdentGruppe.FOLKEREGISTERIDENT)
            ), fnr.value
        ).finnForetrukketIdent()

        foretrukketIdent.shouldBeInstanceOf<IdentFunnet>()
        foretrukketIdent.ident shouldBe fnr
    }

    fun hentPersonQuery(hentPersonPayload: String): String {
        return """
            {
                "data": {
                    "hentPerson": ${hentPersonPayload}
                },
                "errors": null
            }
        """.trimIndent()
    }

    fun response(
        gtType: GtType, gtKommune: String? = null, gtBydel: String? = null, gtLand: String? = null
    ): GraphQLClientResponse<HentGtQuery.Result> {
        return object : GraphQLClientResponse<HentGtQuery.Result> {
            override val data = HentGtQuery.Result(
                GeografiskTilknytning(
                    gtType = gtType, gtKommune = gtKommune, gtBydel = gtBydel, gtLand = gtLand
                )
            )
            override val errors = null
        }
    }
}