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
import no.nav.services.KontorForGtFeil
import no.nav.services.KontorForGtNrFantFallbackKontorForManglendeGt
import org.junit.jupiter.api.Test

class GTNorgServiceTest {
    val fnr = Fnr("12345678901")

    @Test
    fun `skal svare KontorForGtFinnesIkke for bruker uten GT og uten fallback kontor`() = runTest {
        val gtForBruker = GtForBrukerIkkeFunnet("Ingen geografisk tilknytning funnet for bruker")
        val gtService = GTNorgService(
            { gtForBruker },
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
            HarStrengtFortroligAdresse(false),
            gtForBruker
        )
    }

    @Test
    fun `skal feile (ikke bruke fallback) når GtForBruker er teknisk feil`() = runTest {
        val gtForBruker = GtForBrukerOppslagFeil("http 404 fra NORG fant ingen kontor for gt")
        val gtService = GTNorgService(
            { gtForBruker },
            { a, b, c -> throw IllegalStateException("Ikke implementert") },
            { a, b, c -> throw IllegalStateException("Ikke implementert") }
        )

        val kontorForGtResult = gtService.hentGtKontorForBruker(
            fnr,
            HarStrengtFortroligAdresse(false),
            HarSkjerming(false)
        )

        kontorForGtResult shouldBe KontorForGtFeil(gtForBruker.message)
    }

    @Test
    fun `skal svare fallback når gt for bruker når gt ikke finnes men det finnes fallback`() = runTest {
        val fallbackKontor = KontorId("4444")
        val skjerming = HarSkjerming(false)
        val adresse = HarStrengtFortroligAdresse(false)
        val gtForBruker = GtForBrukerIkkeFunnet("Fant ikke")
        val gtService = GTNorgService(
            { gtForBruker },
            { a, b, c -> KontorForGtFinnesIkke(skjerming, adresse, gtForBruker) },
            { a, b, c -> KontorForBrukerMedMangelfullGtFunnet(fallbackKontor, gtForBruker) }
        )

        val kontorForGtResult = gtService.hentGtKontorForBruker(fnr, adresse, skjerming)

        kontorForGtResult shouldBe KontorForGtNrFantFallbackKontorForManglendeGt(fallbackKontor, skjerming, adresse, gtForBruker)
    }

    @Test
    fun `skal svare med feil når fallback feiler`() = runTest {
        val skjerming = HarSkjerming(false)
        val adresse = HarStrengtFortroligAdresse(false)
        val gtForBruker = GtForBrukerIkkeFunnet("Fant ikke")
        val gtService = GTNorgService(
            { gtForBruker },
            { a, b, c -> KontorForGtFinnesIkke(skjerming, adresse, gtForBruker) },
            { a, b, c -> KontorForBrukerMedMangelfullGtFeil("Feil i fallback gt oppslag") }
        )

        val kontorForGtResult = gtService.hentGtKontorForBruker(fnr, adresse, skjerming)

        kontorForGtResult shouldBe KontorForGtFeil("Feil i fallback gt oppslag")
    }

    @Test
    fun `skal svare med fallback-kontor for bruker uten GT og med fallback kontor`() = runTest {
        val fallbackKontor = KontorId("4444")
        val gtForBruker = GtForBrukerIkkeFunnet("Ingen geografisk tilknytning funnet for bruker")
        val gtService = GTNorgService(
            { gtForBruker },
            { a, b, c -> throw IllegalStateException("Ikke implementert") },
            { a, b, c -> KontorForBrukerMedMangelfullGtFunnet(fallbackKontor, gtForBruker) }
        )

        val kontorForGtResult = gtService.hentGtKontorForBruker(
            fnr,
            HarStrengtFortroligAdresse(false),
            HarSkjerming(false)
        )

        kontorForGtResult shouldBe KontorForGtNrFantFallbackKontorForManglendeGt(
            fallbackKontor,
            HarSkjerming(false),
            HarStrengtFortroligAdresse(false),
            GtForBrukerIkkeFunnet("Ingen geografisk tilknytning funnet for bruker")
        )
    }

    @Test
    fun `skal svare KontorForGtNrFantLand når gt er et land`() = runTest {
        val gtLand = GeografiskTilknytningLand("DNK")
        val navUtlandKontor = KontorId("4444")
        val gtForBruker = GtLandForBrukerFunnet(gtLand)
        val gtService = GTNorgService(
            { gtForBruker },
            { a, b, c -> throw IllegalStateException("Ikke implementert") },
            { a, b, c -> KontorForBrukerMedMangelfullGtFunnet(navUtlandKontor,gtForBruker) }
        )

        val kontorForGtResult = gtService.hentGtKontorForBruker(
            fnr,
            HarStrengtFortroligAdresse(false),
            HarSkjerming(false)
        )

        kontorForGtResult shouldBe KontorForGtNrFantFallbackKontorForManglendeGt(
            navUtlandKontor,
            HarSkjerming(false),
            HarStrengtFortroligAdresse(false),
            gtForBruker
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

        kontorForGtResult shouldBe KontorForGtFeil("Feil")
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

        kontorForGtResult shouldBe KontorForGtFeil("Klarte ikke hente GT kontor for bruker: Feil som ble kastet")
    }

}
