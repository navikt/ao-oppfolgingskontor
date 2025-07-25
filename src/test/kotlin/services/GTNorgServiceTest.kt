package services

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import no.nav.db.Fnr
import no.nav.domain.HarSkjerming
import no.nav.domain.HarStrengtFortroligAdresse
import no.nav.domain.KontorId
import no.nav.http.client.GeografiskTilknytningBydelNr
import no.nav.http.client.GeografiskTilknytningLand
import no.nav.http.client.GtForBrukerIkkeFunnet
import no.nav.http.client.GtForBrukerOppslagFeil
import no.nav.http.client.GtLandForBrukerFunnet
import no.nav.http.client.GtNummerForBrukerFunnet
import no.nav.services.GTNorgService
import no.nav.services.KontorForBrukerMedMangelfullGtFeil
import no.nav.services.KontorForBrukerMedMangelfullGtFunnet
import no.nav.services.KontorForBrukerMedMangelfullGtIkkeFunnet
import no.nav.services.KontorForGtFinnesIkke
import no.nav.services.KontorForGtNrFantDefaultKontor
import no.nav.services.KontorForGtNrFantFallbackKontor
import no.nav.services.KontorForGtNrFantLand
import no.nav.services.KontorForGtNrFeil
import org.junit.jupiter.api.Test

class GTNorgServiceTest {
    val fnr = Fnr("12345678901")

    @Test
    fun `skal svare KontorForGtFinnesIkke for bruker uten GT og uten fallback kontor`() = runTest {
        val gtService = GTNorgService(
            { GtForBrukerIkkeFunnet("Ingen geografisk tilknytning funnet for bruker") },
            { a, b, c -> throw IllegalStateException("Ikke implementert") },
            { a, b, c -> KontorForBrukerMedMangelfullGtIkkeFunnet(a) }
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
    fun `skal feile (ikke bruke fallback) når GtForBruker er teknisk feil`() = runTest {
        val fallbackKontor = KontorId("4444")
        val gtService = GTNorgService(
            { GtForBrukerOppslagFeil("Teknisk feil i gt oppslag") },
            { a, b, c -> throw IllegalStateException("Ikke implementert") },
            { a, b, c -> KontorForBrukerMedMangelfullGtFunnet(fallbackKontor) }
        )

        val kontorForGtResult = gtService.hentGtKontorForBruker(
            fnr,
            HarStrengtFortroligAdresse(false),
            HarSkjerming(false)
        )

        kontorForGtResult shouldBe KontorForGtNrFeil("Teknisk feil i gt oppslag")
    }

    @Test
    fun `skal svare fallback når gt for bruker når gt ikke finnes men det finnes fallback`() = runTest {
        val fallbackKontor = KontorId("4444")
        val skjerming = HarSkjerming(false)
        val adresse = HarStrengtFortroligAdresse(false)
        val gtService = GTNorgService(
            { GtForBrukerIkkeFunnet("Fant ikke") },
            { a, b, c -> KontorForGtFinnesIkke(skjerming, adresse) },
            { a, b, c -> KontorForBrukerMedMangelfullGtFunnet(fallbackKontor) }
        )

        val kontorForGtResult = gtService.hentGtKontorForBruker(fnr, adresse, skjerming)

        kontorForGtResult shouldBe KontorForGtNrFantFallbackKontor(fallbackKontor, skjerming, adresse, null)
    }

    @Test
    fun `skal svare med feil når fallback feiler`() = runTest {
        val skjerming = HarSkjerming(false)
        val adresse = HarStrengtFortroligAdresse(false)
        val gtService = GTNorgService(
            { GtForBrukerIkkeFunnet("Fant ikke") },
            { a, b, c -> KontorForGtFinnesIkke(skjerming, adresse) },
            { a, b, c -> KontorForBrukerMedMangelfullGtFeil("Feil i fallback gt oppslag") }
        )

        val kontorForGtResult = gtService.hentGtKontorForBruker(fnr, adresse, skjerming)

        kontorForGtResult shouldBe KontorForGtNrFeil("Feil i fallback gt oppslag")
    }

    @Test
    fun `skal svare med fallback-kontor for bruker uten GT og med fallback kontor`() = runTest {
        val fallbackKontor = KontorId("4444")
        val gtService = GTNorgService(
            { GtForBrukerIkkeFunnet("Ingen geografisk tilknytning funnet for bruker") },
            { a, b, c -> throw IllegalStateException("Ikke implementert") },
            { a, b, c -> KontorForBrukerMedMangelfullGtFunnet(fallbackKontor) }
        )

        val kontorForGtResult = gtService.hentGtKontorForBruker(
            fnr,
            HarStrengtFortroligAdresse(false),
            HarSkjerming(false)
        )

        kontorForGtResult shouldBe KontorForGtNrFantFallbackKontor(
            fallbackKontor,
            HarSkjerming(false),
            HarStrengtFortroligAdresse(false),
            null
        )
    }

    @Test
    fun `skal svare KontorForGtNrFantLand når gt er et land`() = runTest {
        val gtLand = GeografiskTilknytningLand("DNK")
        val gtService = GTNorgService(
            { GtLandForBrukerFunnet(gtLand) },
            { a, b, c -> throw IllegalStateException("Ikke implementert") },
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
    fun `skal svare med gt-kontor når gt er nr`() = runTest {
        val gtNr = GeografiskTilknytningBydelNr("131")
        val kontor = KontorId("1234")
        val gtService = GTNorgService(
            { GtNummerForBrukerFunnet(gtNr) },
            { a, b, c -> KontorForGtNrFantDefaultKontor(kontor, c, b, gtNr) },
            { a, b, c -> throw IllegalStateException("Ikke implementert") },
        )

        val kontorForGtResult = gtService.hentGtKontorForBruker(
            fnr,
            HarStrengtFortroligAdresse(false),
            HarSkjerming(false)
        )

        kontorForGtResult shouldBe KontorForGtNrFantDefaultKontor(
            kontor,
            HarSkjerming(false),
            HarStrengtFortroligAdresse(false),
            gtNr
        )
    }

    @Test
    fun `skal håndtere at gt oppslag returnerer feil`() = runTest {
        val gtService = GTNorgService(
            { GtForBrukerOppslagFeil("Feil") },
            { a, b, c -> throw IllegalStateException("Ikke implementert") },
            { a, b, c -> throw IllegalStateException("Ikke implementert") },
        )

        val kontorForGtResult = gtService.hentGtKontorForBruker(
            fnr,
            HarStrengtFortroligAdresse(false),
            HarSkjerming(false)
        )

        kontorForGtResult shouldBe KontorForGtNrFeil("Feil")
    }

    @Test
    fun `skal håndtere at feil kastes gt oppslag`() = runTest {
        val gtService = GTNorgService(
            { throw Exception("Feil som ble kastet") },
            { a, b, c -> throw IllegalStateException("Ikke implementert") },
            { a, b, c -> throw IllegalStateException("Ikke implementert") },
        )

        val kontorForGtResult = gtService.hentGtKontorForBruker(
            fnr,
            HarStrengtFortroligAdresse(false),
            HarSkjerming(false)
        )

        kontorForGtResult shouldBe KontorForGtNrFeil("Klarte ikke hente GT kontor for bruker: Feil som ble kastet")
    }

}
