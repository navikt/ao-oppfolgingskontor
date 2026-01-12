package services

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import no.nav.db.Fnr
import no.nav.db.Ident
import no.nav.domain.HarSkjerming
import no.nav.domain.HarStrengtFortroligAdresse
import no.nav.domain.KontorId
import no.nav.http.client.GeografiskTilknytningBydelNr
import no.nav.http.client.GeografiskTilknytningLand
import domain.gtForBruker.GtForBrukerIkkeFunnet
import domain.gtForBruker.GtForBrukerOppslagFeil
import domain.gtForBruker.GtLandForBrukerFunnet
import domain.gtForBruker.GtNummerForBrukerFunnet
import no.nav.services.GTNorgService
import domain.kontorForGt.KontorForBrukerMedMangelfullGtFeil
import domain.kontorForGt.KontorForBrukerMedMangelfullGtFunnet
import domain.kontorForGt.KontorForBrukerMedMangelfullGtIkkeFunnet
import domain.kontorForGt.KontorForGtFantIkkeKontor
import domain.kontorForGt.KontorForGtFantDefaultKontor
import domain.kontorForGt.KontorForGtFeil
import domain.kontorForGt.KontorForGtNrFantFallbackKontorForManglendeGt
import org.junit.jupiter.api.Test

class GTNorgServiceTest {
    val fnr = Fnr("12345678901", Ident.HistoriskStatus.UKJENT)

    @Test
    fun `skal svare KontorForGtFinnesIkke for bruker uten GT og uten fallback kontor`() = runTest {
        val gtForBruker = GtForBrukerIkkeFunnet("Ingen geografisk tilknytning funnet for bruker")
        val gtService = GTNorgService(
            { gtForBruker },
            { a, b, c -> throw IllegalStateException("Ikke implementert") },
            { a, b, c -> KontorForBrukerMedMangelfullGtIkkeFunnet(a) },
            { a,b,c,d -> throw IllegalStateException("Ikke implementert") }
        )

        val kontorForGtResult = gtService.hentGtKontorForBruker(
            fnr,
            HarStrengtFortroligAdresse(false),
            HarSkjerming(false)
        )

        kontorForGtResult shouldBe KontorForGtFantIkkeKontor(
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
            { a, b, c -> throw IllegalStateException("Ikke implementert") },
            { a,b,c,d -> throw IllegalStateException("Ikke implementert") }
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
            { a, b, c -> KontorForGtFantIkkeKontor(skjerming, adresse, gtForBruker) },
            { a, b, c -> KontorForBrukerMedMangelfullGtFunnet(fallbackKontor, gtForBruker) },
            { a,b,c,d -> throw IllegalStateException("Ikke implementert") }
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
            { a, b, c -> KontorForGtFantIkkeKontor(skjerming, adresse, gtForBruker) },
            { a, b, c -> KontorForBrukerMedMangelfullGtFeil("Feil i fallback gt oppslag") },
            { a,b,c,d -> throw IllegalStateException("Ikke implementert") }
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
            { a, b, c -> KontorForBrukerMedMangelfullGtFunnet(fallbackKontor, gtForBruker) },
            { a,b,c,d -> throw IllegalStateException("Ikke implementert") }
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
            { a, b, c -> KontorForBrukerMedMangelfullGtFunnet(navUtlandKontor,gtForBruker) },
            { a,b,c,d -> throw IllegalStateException("Ikke implementert") }
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
            { a, b, c -> KontorForGtFantDefaultKontor(kontor, c, b, gtNr) },
            { a, b, c -> throw IllegalStateException("Ikke implementert") },
            { a,b,c,d -> throw IllegalStateException("Ikke implementert") }
        )

        val kontorForGtResult = gtService.hentGtKontorForBruker(
            fnr,
            HarStrengtFortroligAdresse(false),
            HarSkjerming(false)
        )

        kontorForGtResult shouldBe KontorForGtFantDefaultKontor(
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
            { a,b,c,d -> throw IllegalStateException("Ikke implementert") }
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
            { a,b,c,d -> throw IllegalStateException("Ikke implementert") }
        )

        val kontorForGtResult = gtService.hentGtKontorForBruker(
            fnr,
            HarStrengtFortroligAdresse(false),
            HarSkjerming(false)
        )

        kontorForGtResult shouldBe KontorForGtFeil("Klarte ikke hente GT kontor for bruker: Feil som ble kastet")
    }

}
