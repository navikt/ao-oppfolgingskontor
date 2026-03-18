package services

import eventsLogger.LoggSattKontorEvent
import io.kotest.matchers.shouldBe
import no.nav.NavAnsatt
import no.nav.domain.KontorId
import no.nav.domain.NavIdent
import no.nav.services.KontorTilordningService
import no.nav.utils.flywayMigrationInTest
import no.nav.utils.gittBrukerUnderOppfolging
import no.nav.utils.gittIdentMedKontor
import no.nav.utils.randomFnr
import org.junit.jupiter.api.Test
import java.util.UUID

class `KontorSammenslåingServiceTest` {

    @Test
    fun `skal stoppe å kjøre batcher når alle brukere er flyttet`() {
        // Denne testen har ikke asserts, men den terminerer ikke hvis feilen er tilstedet
        flywayMigrationInTest()
        val loggSattKontorEvent: LoggSattKontorEvent = { a, b, c -> Unit }
        val kontorTilordningService = KontorTilordningService(loggSattKontorEvent)
        val service = KontorSammenslåingService(kontorTilordningService::tilordneKontor)
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
        val loggSattKontorEvent: LoggSattKontorEvent = { a, b, c -> Unit }
        val kontorTilordningService = KontorTilordningService(loggSattKontorEvent)
        val service = KontorSammenslåingService(kontorTilordningService::tilordneKontor)
        val ident = randomFnr()
        val kontor = KontorId("8362")

        val oppfolgingsperiode = gittBrukerUnderOppfolging(ident)
        gittIdentMedKontor(ident, kontor, oppfolgingsperiode)

        service.antallKontorerSomSkalEndres(listOf(kontor)) shouldBe 1
    }

}