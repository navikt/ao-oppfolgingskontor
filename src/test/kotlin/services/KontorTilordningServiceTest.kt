package services

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import no.nav.db.Fnr
import no.nav.db.entity.ArbeidsOppfolgingKontorEntity
import no.nav.db.entity.KontorHistorikkEntity
import no.nav.db.table.KontorhistorikkTable
import no.nav.domain.OppfolgingsperiodeId
import no.nav.domain.events.OppfolgingsperiodeStartetNoeTilordning
import no.nav.services.KontorTilordningService
import no.nav.utils.flywayMigrationInTest
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import java.util.*


class KontorTilordningServiceTest {

    @Test
    fun `Kontortilordning skal peke på historikkentry`() {
        flywayMigrationInTest()
        val fnr = "01078598765"
        val oppfolginsperiodeUuid = OppfolgingsperiodeId(UUID.randomUUID())
        val kontorEndretEvent = OppfolgingsperiodeStartetNoeTilordning(Fnr(fnr), oppfolginsperiodeUuid)

        KontorTilordningService.tilordneKontor(kontorEndretEvent)
        KontorTilordningService.tilordneKontor(kontorEndretEvent)

        val (arbeidsoppfolgingskontor, historikkEntries) = transaction {
            val arbeidsoppfolgingskontor = ArbeidsOppfolgingKontorEntity[fnr]
            val historikkEntries = KontorHistorikkEntity
                .find { KontorhistorikkTable.ident eq fnr }
                .toList()
            arbeidsoppfolgingskontor to historikkEntries
        }

        historikkEntries shouldHaveSize 2
        val sisteEntry = historikkEntries.maxBy { it.id.value }
        arbeidsoppfolgingskontor.historikkEntry shouldBe sisteEntry.id.value
    }
}