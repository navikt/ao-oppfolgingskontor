package services

import no.nav.db.Fnr
import no.nav.db.entity.ArbeidsOppfolgingKontorEntity
import no.nav.db.entity.KontorHistorikkEntity
import no.nav.db.table.ArbeidsOppfolgingKontorTable
import no.nav.domain.OppfolgingsperiodeId
import no.nav.domain.events.ArenaKontorEndret
import no.nav.domain.events.OppfolgingsperiodeStartetNoeTilordning
import no.nav.services.KontorTilordningService
import no.nav.utils.flywayMigrationInTest
import org.jetbrains.exposed.sql.selectAll
import org.junit.jupiter.api.Test
import java.util.UUID


class KontorTilordningServiceTest {

    @Test
    fun `Kontortilordning skal peke på historikkentry`() {
        flywayMigrationInTest()
        val fnr = "01078598765"
        val oppfolginsperiodeUuid = OppfolgingsperiodeId(UUID.randomUUID())
        val kontorEndretEvent = OppfolgingsperiodeStartetNoeTilordning(Fnr(fnr), oppfolginsperiodeUuid)

        KontorTilordningService.tilordneKontor(kontorEndretEvent)

        val arbeidsoppfolgingskontor = ArbeidsOppfolgingKontorEntity.get(fnr)
        val historikkEntry = KontorHistorikkEntity.
        ArbeidsOppfolgingKontorTable.selectAll()

    }
}