package services

import eventsLogger.LoggSattKontorEvent
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.ktor.test.dispatcher.testSuspend
import no.nav.NavAnsatt
import no.nav.domain.KontorId
import no.nav.domain.KontorNavn
import no.nav.domain.NavIdent
import no.nav.domain.OppfolgingsperiodeId
import no.nav.services.KontorTilordningService
import no.nav.utils.TestDb
import no.nav.utils.flywayMigrationInTest
import no.nav.utils.gittBrukerUnderOppfolging
import no.nav.utils.gittIdentIMapping
import no.nav.utils.gittIdentMedKontor
import no.nav.utils.gittKontorNavn
import no.nav.utils.randomAktorId
import no.nav.utils.randomFnr
import no.nav.utils.randomInternIdent
import org.junit.jupiter.api.Test
import java.util.UUID

class `KontorSammenslåingServiceTest` {

    @Test
    fun `skal stoppe å kjøre batcher når alle brukere er flyttet`() = testSuspend {
        // Denne testen har ikke asserts, men den terminerer ikke hvis feilen er tilstedet
        flywayMigrationInTest()
        val loggSattKontorEvent: LoggSattKontorEvent = { _, _, _, _ -> }
        val kontorTilordningService = KontorTilordningService(loggSattKontorEvent)

        val publiserteKontorer = mutableListOf<KontortilordningSomSkalRepubliseres>()
        val republiseringService = KontorRepubliseringService(
            republiserKontor = {
                publiserteKontorer.add(it)
                Result.success(Unit)
            },
            datasource = TestDb.postgres,
            friskOppAlleKontorNavn = {},
            hentInternIdenterForBrukere = { throw NotImplementedError() },
            publiserTombstone = { throw NotImplementedError() },
            hentOppfolgingsperiode = { throw NotImplementedError() }
        )
        val service = KontorSammenslåingService(kontorTilordningService::tilordneKontor, republiseringService)
        val ident = randomFnr()
        val kontor = KontorId("8361")

        val oppfolgingsperiode = gittBrukerUnderOppfolging(ident)
        gittIdentMedKontor(ident, kontor, oppfolgingsperiode)

        service.slåSammenKontorer(
            NavAnsatt(NavIdent("G112211"), UUID.randomUUID()),
            KontorSammenSlåing(listOf(kontor), KontorId("2121"))
        )
    }

    @Test
    fun `skal telle antall personer på et kontor`() {
        flywayMigrationInTest()
        val loggSattKontorEvent: LoggSattKontorEvent = { _, _, _, _ -> }
        val kontorTilordningService = KontorTilordningService(loggSattKontorEvent)
        val republiseringService = KontorRepubliseringService(
            republiserKontor = { throw NotImplementedError() },
            datasource = TestDb.postgres,
            friskOppAlleKontorNavn = {},
            hentInternIdenterForBrukere = { throw NotImplementedError() },
            publiserTombstone = { throw NotImplementedError() },
            hentOppfolgingsperiode = { throw NotImplementedError() }
        )
        val service = KontorSammenslåingService(kontorTilordningService::tilordneKontor, republiseringService)
        val ident = randomFnr()
        val kontor = KontorId("8362")

        val oppfolgingsperiode = gittBrukerUnderOppfolging(ident)
        gittIdentMedKontor(ident, kontor, oppfolgingsperiode)

        service.antallKontorerSomSkalEndres(listOf(kontor)) shouldBe 1
    }

    @Test
    fun `skal republisere flyttede brukere`() = testSuspend {
        // Denne testen har ikke asserts, men den terminerer ikke hvis feilen er tilstedet
        flywayMigrationInTest()
        val loggSattKontorEvent: LoggSattKontorEvent = { _, _, _, _ -> }
        val kontorTilordningService = KontorTilordningService(loggSattKontorEvent)
        val kontor = KontorId("8361")

        val fnr = randomFnr()
        val aktorId = randomAktorId()
        val periodeId = OppfolgingsperiodeId(UUID.randomUUID())
        gittBrukerUnderOppfolging(fnr, periodeId)
        gittIdentMedKontor(fnr, kontor, periodeId)
        gittIdentIMapping(listOf(fnr, aktorId), null, randomInternIdent())
        gittKontorNavn(KontorNavn("Testkontoret"), kontor)

        val publiserteKontorer = mutableListOf<KontortilordningSomSkalRepubliseres>()
        val republiseringService = KontorRepubliseringService(
            republiserKontor = {
                publiserteKontorer.add(it)
                Result.success(Unit)
            },
            datasource = TestDb.postgres,
            friskOppAlleKontorNavn = {},
            hentInternIdenterForBrukere = { throw NotImplementedError() },
            publiserTombstone = { throw NotImplementedError() },
            hentOppfolgingsperiode = { throw NotImplementedError() }
        )
        val service = KontorSammenslåingService(kontorTilordningService::tilordneKontor, republiseringService)
        val ident = randomFnr()

        val oppfolgingsperiode = gittBrukerUnderOppfolging(ident)
        gittIdentMedKontor(ident, kontor, oppfolgingsperiode)

        service.slåSammenKontorer(
            NavAnsatt(NavIdent("G112211"), UUID.randomUUID()),
            KontorSammenSlåing(listOf(kontor), KontorId("2121"))
        )
        publiserteKontorer shouldHaveSize 1
    }

}