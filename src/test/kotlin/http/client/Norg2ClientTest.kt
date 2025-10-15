package http.client

import io.kotest.matchers.shouldBe
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import no.nav.domain.HarSkjerming
import no.nav.domain.HarStrengtFortroligAdresse
import no.nav.domain.KontorId
import no.nav.http.client.GeografiskTilknytningBydelNr
import domain.gtForBruker.GtForBrukerIkkeFunnet
import no.nav.http.client.Norg2Client
import no.nav.http.client.Norg2Client.Companion.arbeidsfordelingPath
import no.nav.http.client.NorgKontor
import no.nav.http.client.mockNorg2Host
import domain.kontorForGt.KontorForBrukerMedMangelfullGtFeil
import domain.kontorForGt.KontorForBrukerMedMangelfullGtFunnet
import domain.kontorForGt.KontorForBrukerMedMangelfullGtIkkeFunnet
import domain.kontorForGt.KontorForGtFantDefaultKontor
import domain.kontorForGt.KontorForGtFeil
import org.junit.jupiter.api.Test

class Norg2ClientTest {

    val skjermetKontor = "9999"
    val adressebeskyttetKontor = "8888"
    val vanligKontor = "7777"
    val gt = GeografiskTilknytningBydelNr("434576")
    val errorGt = GeografiskTilknytningBydelNr("634576")

    fun ApplicationTestBuilder.mockNorg2Ruting(fallbackKontor: KontorId? = null): Norg2Client {
        return mockNorg2Host {
            get( "/norg2/api/v1/enhet/navkontor/{gt}") {
                val gt = call.pathParameters["gt"] ?: error("Geografisk tilknytning må være satt")
                if (gt == errorGt.value) {
                    call.respond(HttpStatusCode.InternalServerError)
                }
                val skjermet = call.pathParameters["skjermet"].toBoolean()
                val strengtFortroligAdresse = call.pathParameters["diskresjonskode"] == "SPSF"
                if (skjermet) {
                    call.respond(defaultNorgKontor().copy(enhetNr = skjermetKontor))
                } else if (strengtFortroligAdresse) {
                    call.respond(defaultNorgKontor().copy(enhetNr = adressebeskyttetKontor))
                } else {
                    call.respond(defaultNorgKontor().copy(enhetNr = vanligKontor))
                }
            }
            post(arbeidsfordelingPath) {
                call.respond(listOfNotNull(
                    if (fallbackKontor != null) defaultNorgKontor().copy(enhetNr = fallbackKontor.id) else null
                ))
            }
        }
    }

    @Test
    fun `skal kalle norg uten skjerming, med spsf hvis ikke strengtFortroligAdresse er satt true`() = testApplication {
        val client = mockNorg2Ruting()
        val response = client.hentKontorForGt(
            gt,
            HarStrengtFortroligAdresse(true),
            HarSkjerming(false)
        )

        response shouldBe KontorForGtFantDefaultKontor(
            kontorId = KontorId(vanligKontor),
            HarSkjerming(false),
            HarStrengtFortroligAdresse(true),
            gt
        )
    }

    @Test
    fun `skal kalle norg med skjerming og uten spsf hvis hentKontorForGt blir kalt med skjerming`() = testApplication {
        val client = mockNorg2Ruting()
        val response = client.hentKontorForGt(
            gt,
            HarStrengtFortroligAdresse(false),
            HarSkjerming(true)
        )

        response shouldBe KontorForGtFantDefaultKontor(
            kontorId = KontorId(vanligKontor),
            HarSkjerming(true),
            HarStrengtFortroligAdresse(false),
            gt
        )
    }

    @Test
    fun `skal kalle norg uten skjerming og spsf hvis ikke sensitiv`() = testApplication {
        val client = mockNorg2Ruting()
        val response = client.hentKontorForGt(
            gt,
            HarStrengtFortroligAdresse(false),
            HarSkjerming(false)
        )

        response shouldBe KontorForGtFantDefaultKontor(
            kontorId = KontorId(vanligKontor),
            HarSkjerming(false),
            HarStrengtFortroligAdresse(false),
            gt
        )
    }

    @Test
    fun `skal håndtere at norg svarer med 500`() = testApplication {
        val client = mockNorg2Ruting()
        val response = client.hentKontorForGt(
            errorGt,
            HarStrengtFortroligAdresse(false),
            HarSkjerming(false)
        )

        response shouldBe KontorForGtFeil("Kunne ikke hente kontor for GT i norg, http-status: 500 Internal Server Error, gt: 634576 Bydel, body: ")
    }

    @Test
    fun `skal svare med arbeidsfordelingkontor hvis det finnes`() = testApplication {
        val fallbackKontor = KontorId("1199")
        val client = mockNorg2Ruting(fallbackKontor = fallbackKontor)

        val response = client.hentKontorForBrukerMedMangelfullGT(
            GtForBrukerIkkeFunnet("Gt mangler"),
            HarStrengtFortroligAdresse(false),
            HarSkjerming(false)
        )

        response shouldBe KontorForBrukerMedMangelfullGtFunnet(fallbackKontor, GtForBrukerIkkeFunnet("Gt mangler"))
    }

    @Test
    fun `skal svare med KontorForBrukerMedMangelfullGtIkkeFunnet hvis det ikke finnes`() = testApplication {
        val client = mockNorg2Ruting() // Ingen mocking av fallback-kontor

        val response = client.hentKontorForBrukerMedMangelfullGT(
            GtForBrukerIkkeFunnet("Ikke funnet"),
            HarStrengtFortroligAdresse(false),
            HarSkjerming(false)
        )

        response shouldBe KontorForBrukerMedMangelfullGtIkkeFunnet(GtForBrukerIkkeFunnet("Ikke funnet"))
    }

    @Test
    fun `skal svare med KontorForBrukerMedMangelfullGtFeil hvis det ikke finnes`() = testApplication {
        val client = mockNorg2Host {
            post(arbeidsfordelingPath) {
                call.respond(HttpStatusCode.InternalServerError, "Feil fra server")
            }
        }
        val response = client.hentKontorForBrukerMedMangelfullGT(
            GtForBrukerIkkeFunnet("Ikke funnet"),
            HarStrengtFortroligAdresse(false),
            HarSkjerming(false)
        )

        response shouldBe KontorForBrukerMedMangelfullGtFeil("Kunne ikke hente kontor for GT i norg med arbeidsfordeling: HTTP POST mot arbeidsfordeling feilet med http-status: 500 Internal Server Error, gt: GtForBrukerIkkeFunnet(message=Ikke funnet)")
    }

    private fun defaultNorgKontor(): NorgKontor {
        return NorgKontor(
            enhetId = 1234,
            navn = "Test Enhet",
            enhetNr = "666",
            antallRessurser = -1,
            status = "HØY STATUS",
            orgNivaa = "HØYT NIVÅ",
            type = "LOKAL",
            organisasjonsnummer = null,
            underEtableringDato = null,
            aktiveringsdato = null,
            underAvviklingDato = null,
            nedleggelsesdato = null,
            false,
            3,
            sosialeTjenester = null,
            kanalstrategi = null,
            orgNrTilKommunaltNavKontor = null
        )
    }
}
