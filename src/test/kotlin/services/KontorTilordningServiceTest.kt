package services

import db.table.AlternativAoKontorTable
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import no.nav.db.Fnr
import no.nav.db.Ident.HistoriskStatus.AKTIV
import no.nav.db.entity.ArbeidsOppfolgingKontorEntity
import no.nav.db.entity.ArenaKontorEntity
import no.nav.db.entity.KontorHistorikkEntity
import no.nav.db.table.KontorhistorikkTable
import no.nav.domain.KontorId
import no.nav.domain.KontorTilordning
import no.nav.domain.OppfolgingsperiodeId
import no.nav.domain.events.ArenaKontorFraOppfolgingsbrukerVedOppfolgingStartMedEtterslep
import no.nav.domain.events.OppfolgingsperiodeStartetNoeTilordning
import no.nav.kafka.consumers.KontorEndringer
import no.nav.services.KontorTilordningService
import no.nav.utils.flywayMigrationInTest
import no.nav.utils.randomFnr
import org.jetbrains.exposed.dao.exceptions.EntityNotFoundException
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.*


class KontorTilordningServiceTest {

    @Test
    fun `Kontortilordning skal peke på historikkentry`() {
        flywayMigrationInTest()
        val fnr = "01078598765"
        val oppfolginsperiodeUuid = OppfolgingsperiodeId(UUID.randomUUID())
        val kontorEndretEvent = OppfolgingsperiodeStartetNoeTilordning(Fnr(fnr, AKTIV), oppfolginsperiodeUuid)

        KontorTilordningService.tilordneKontor(kontorEndretEvent, true)
        KontorTilordningService.tilordneKontor(kontorEndretEvent, true)

        val (arbeidsoppfolgingskontor, historikkEntries) = transaction {
            val arbeidsoppfolgingskontor = ArbeidsOppfolgingKontorEntity[fnr]
            val historikkEntries = KontorHistorikkEntity
                .find { KontorhistorikkTable.ident eq fnr }
                .toList()
            arbeidsoppfolgingskontor to historikkEntries
        }

        historikkEntries shouldHaveSize 2
        val sisteEntry = historikkEntries.maxBy { it.id.value }
        arbeidsoppfolgingskontor.historikkEntry shouldBe sisteEntry.id
    }

    @Test
    fun `skal lagre arena og ao kontor`() {
        flywayMigrationInTest()
        val fnr = "01078598765"
        val oppfolginsperiodeUuid = OppfolgingsperiodeId(UUID.randomUUID())
        val aoEndring =  OppfolgingsperiodeStartetNoeTilordning(Fnr(fnr, AKTIV), oppfolginsperiodeUuid)
        val arenaEndring = ArenaKontorFraOppfolgingsbrukerVedOppfolgingStartMedEtterslep(
            KontorTilordning(
                Fnr(fnr, AKTIV),
                KontorId("1122"),
                oppfolginsperiodeUuid
            ),
            sistEndretIArena = OffsetDateTime.now(),
        )

        KontorTilordningService.tilordneKontor(KontorEndringer(
            aoKontorEndret = aoEndring,
            arenaKontorEndret = arenaEndring,
        ), true)

        transaction { ArbeidsOppfolgingKontorEntity[fnr].kontorId } shouldBe "4154"
        transaction { ArenaKontorEntity[fnr].kontorId } shouldBe "1122"
    }

    @Test
    fun `skal lagre arena-kontor i aokontor-tabell og arenakontor-tabell når brukAoRuting er false`() {
        flywayMigrationInTest()
        val fnr = randomFnr().value
        val oppfolginsperiodeUuid = OppfolgingsperiodeId(UUID.randomUUID())
        val arenaEndring = ArenaKontorFraOppfolgingsbrukerVedOppfolgingStartMedEtterslep(
            KontorTilordning(
                Fnr(fnr, AKTIV),
                KontorId("2121"),
                oppfolginsperiodeUuid
            ),
            sistEndretIArena = OffsetDateTime.now(),
        )

        KontorTilordningService.tilordneKontor(KontorEndringer(
            arenaKontorEndret = arenaEndring,
        ), brukAoRuting = false)

        transaction { ArbeidsOppfolgingKontorEntity[fnr].fnr.value } shouldBe fnr
        transaction { ArenaKontorEntity[fnr].fnr.value } shouldBe fnr
    }

    @Test
    fun `skal lagre arena-kontor i arenakontor-tabell men ikke i aokontor-tabell når brukAoRuting er true`() {
        flywayMigrationInTest()
        val fnr = randomFnr().value
        val arenaKontorId = "1122"
        val oppfolginsperiodeUuid = OppfolgingsperiodeId(UUID.randomUUID())
        val arenaEndring = ArenaKontorFraOppfolgingsbrukerVedOppfolgingStartMedEtterslep(
            KontorTilordning(
                Fnr(fnr, AKTIV),
                KontorId(arenaKontorId),
                oppfolginsperiodeUuid
            ),
            sistEndretIArena = OffsetDateTime.now(),
        )

        KontorTilordningService.tilordneKontor(KontorEndringer(
            arenaKontorEndret = arenaEndring,
        ), brukAoRuting = true)

        shouldThrow<EntityNotFoundException> {
            transaction { ArbeidsOppfolgingKontorEntity[fnr] }
        }
        transaction { ArenaKontorEntity[fnr].fnr.value } shouldBe fnr
    }

    @Test
    fun `skal lagre ao-kontor i aokontor-tabell når brukAoRuting er true`() {
        flywayMigrationInTest()
        val fnr = randomFnr().value
        val oppfolginsperiodeUuid = OppfolgingsperiodeId(UUID.randomUUID())
        val aoEndring =  OppfolgingsperiodeStartetNoeTilordning(Fnr(fnr, AKTIV), oppfolginsperiodeUuid)

        KontorTilordningService.tilordneKontor(KontorEndringer(
            aoKontorEndret = aoEndring,
        ), brukAoRuting = true)

        transaction { ArbeidsOppfolgingKontorEntity[fnr].fnr.value } shouldBe fnr
    }

    @Test
    fun `skal lagre ao-kontor i alternativ_aokontor-tabell når brukAoRuting er false`() {
        flywayMigrationInTest()
        val fnr = randomFnr().value
        val oppfolginsperiodeUuid = OppfolgingsperiodeId(UUID.randomUUID())
        val aoEndring =  OppfolgingsperiodeStartetNoeTilordning(Fnr(fnr, AKTIV), oppfolginsperiodeUuid)

        KontorTilordningService.tilordneKontor(KontorEndringer(
            aoKontorEndret = aoEndring,
        ), brukAoRuting = false)

        shouldThrow<EntityNotFoundException> {
            transaction { ArbeidsOppfolgingKontorEntity[fnr] }
        }
        transaction { AlternativAoKontorTable.selectAll().map { it[AlternativAoKontorTable.fnr] }.last() } shouldBe fnr
    }
}