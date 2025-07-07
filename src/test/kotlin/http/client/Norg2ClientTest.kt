package http.client

import io.kotest.matchers.shouldBe
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import no.nav.domain.HarSkjerming
import no.nav.domain.HarStrengtFortroligAdresse
import no.nav.domain.KontorId
import no.nav.http.client.GeografiskTilknytningNr
import no.nav.http.client.Norg2Client
import no.nav.http.client.NorgKontor
import no.nav.http.client.mockNorg2Host
import no.nav.services.KontorForGtNrFantKontor
import no.nav.services.KontorForGtNrFeil
import org.junit.jupiter.api.Test

class Norg2ClientTest {

    val skjermetKontor = "9999"
    val adressebeskyttetKontor = "8888"
    val vanligKontor = "7777"
    val gt = GeografiskTilknytningNr("434576")
    val errorGt = GeografiskTilknytningNr("634576")

    fun ApplicationTestBuilder.mockNorg2Ruting(): Norg2Client {
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

        response shouldBe KontorForGtNrFantKontor(
            kontorId = KontorId(vanligKontor),
            HarSkjerming(false),
            HarStrengtFortroligAdresse(true),
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

        response shouldBe KontorForGtNrFantKontor(
            kontorId = KontorId(vanligKontor),
            HarSkjerming(true),
            HarStrengtFortroligAdresse(false),
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

        response shouldBe KontorForGtNrFantKontor(
            kontorId = KontorId(vanligKontor),
            HarSkjerming(false),
            HarStrengtFortroligAdresse(false),
        )
    }

    @Test
    fun `skal håndtere at norg svare med 500`() = testApplication {
        val client = mockNorg2Ruting()
        val response = client.hentKontorForGt(
            errorGt,
            HarStrengtFortroligAdresse(false),
            HarSkjerming(false)
        )

        response shouldBe KontorForGtNrFeil("Kunne ikke hente kontor for GT i norg, http-status: 500 Internal Server Error, gt: 634576")
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
