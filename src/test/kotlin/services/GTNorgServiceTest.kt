package services

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import no.nav.domain.HarSkjerming
import no.nav.domain.HarStrengtFortroligAdresse
import no.nav.domain.KontorId
import no.nav.http.client.GeografiskTilknytningLand
import no.nav.http.client.GeografiskTilknytningNr
import no.nav.http.client.GtForBrukerIkkeFunnet
import no.nav.http.client.GtForBrukerOppslagFeil
import no.nav.http.client.GtLandForBrukerFunnet
import no.nav.http.client.GtNummerForBrukerFunnet
import no.nav.services.GTNorgService
import no.nav.services.KontorForGtFinnesIkke
import no.nav.services.KontorForGtNrFantKontor
import no.nav.services.KontorForGtNrFantLand
import no.nav.services.KontorForGtNrFeil
import org.junit.jupiter.api.Test

class GTNorgServiceTest {
    val fnr = "12345678901"

    @Test
    fun `skal håndtere gt for bruker ikke funnet`() = runTest {
        val gtService = GTNorgService(
            { GtForBrukerIkkeFunnet("Ingen geografisk tilknytning funnet for bruker") },
            { a, b, c -> throw IllegalStateException("Ikke implementert") }
        )

        val kontorForGtResult = gtService.hentGtKontorForBruker(
            fnr,
            HarStrengtFortroligAdresse(false),
            HarSkjerming(false)
        )

        kontorForGtResult shouldBe KontorForGtFinnesIkke(
            HarSkjerming(false),
            HarStrengtFortroligAdresse(false)
        )
    }

    @Test
    fun `skal håndtere gt er land`() = runTest {
        val gtLand = GeografiskTilknytningLand("DNK")
        val gtService = GTNorgService(
            { GtLandForBrukerFunnet(gtLand) },
            { a, b, c -> throw IllegalStateException("Ikke implementert") }
        )

        val kontorForGtResult = gtService.hentGtKontorForBruker(
            fnr,
            HarStrengtFortroligAdresse(false),
            HarSkjerming(false)
        )

        kontorForGtResult shouldBe KontorForGtNrFantLand(
            gtLand,
            HarSkjerming(false),
            HarStrengtFortroligAdresse(false)
        )
    }

    @Test
    fun `skal håndtere gt er nr`() = runTest {
        val gtNr = GeografiskTilknytningNr("131")
        val kontor = KontorId("1234")
        val gtService = GTNorgService(
            { GtNummerForBrukerFunnet(gtNr) },
            { a, b, c -> KontorForGtNrFantKontor(kontor, c, b) }
        )

        val kontorForGtResult = gtService.hentGtKontorForBruker(
            fnr,
            HarStrengtFortroligAdresse(false),
            HarSkjerming(false)
        )

        kontorForGtResult shouldBe KontorForGtNrFantKontor(
            kontor,
            HarSkjerming(false),
            HarStrengtFortroligAdresse(false)
        )
    }

    @Test
    fun `skal håndtere feil kastes i gt oppslag`() = runTest {
        val gtService = GTNorgService(
            { GtForBrukerOppslagFeil("Feil") },
            { a, b, c -> throw IllegalStateException("Ikke implementert") }
        )

        val kontorForGtResult = gtService.hentGtKontorForBruker(
            fnr,
            HarStrengtFortroligAdresse(false),
            HarSkjerming(false)
        )

        kontorForGtResult shouldBe KontorForGtNrFeil("Feil")
    }

    @Test
    fun `skal håndtere feil i gt oppslag`() = runTest {
        val gtService = GTNorgService(
            { throw Exception("Feil som ble kastet") },
            { a, b, c -> throw IllegalStateException("Ikke implementert") }
        )

        val kontorForGtResult = gtService.hentGtKontorForBruker(
            fnr,
            HarStrengtFortroligAdresse(false),
            HarSkjerming(false)
        )

        kontorForGtResult shouldBe KontorForGtNrFeil("Klarte ikke hente GT kontor for bruker: Feil som ble kastet")
    }

}
