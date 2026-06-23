package services

import db.table.AlternativAoKontorTable
import domain.Systemnavn
import eventsLogger.KontorTypeForBigQuery
import eventsLogger.LoggSattKontorEvent
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.time.OffsetDateTime
import java.util.UUID
import no.nav.db.Fnr
import no.nav.db.Ident.HistoriskStatus.AKTIV
import no.nav.db.entity.ArbeidsOppfolgingKontorEntity
import no.nav.db.entity.ArenaKontorEntity
import no.nav.db.entity.KontorHistorikkEntity
import no.nav.domain.KontorEndringsType
import no.nav.db.table.KontorhistorikkTable
import no.nav.domain.KontorId
import no.nav.domain.KontorTilordning
import no.nav.domain.NavIdent
import no.nav.domain.OppfolgingsperiodeId
import no.nav.domain.System
import no.nav.domain.Veileder
import no.nav.domain.events.ArenaKontorFraOppfolgingsbrukerVedOppfolgingStartMedEtterslep
import no.nav.domain.events.KontorSattAvVeileder
import no.nav.domain.events.OppfolgingsperiodeStartetNoeTilordning
import no.nav.kafka.consumers.KontorEndringer
import no.nav.utils.flywayMigrationInTest
import no.nav.utils.gittBrukerUnderOppfolging
import no.nav.utils.kontorTilordningService
import no.nav.utils.randomFnr
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.dao.exceptions.EntityNotFoundException
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test


class KontorTilordningServiceTest {

    private data class LoggetKontorEvent(
        val kontorId: String,
        val fraKontorId: String?,
        val kontorEndringsType: KontorEndringsType?,
        val kontorType: KontorTypeForBigQuery,
    )

    @Test
    fun `Kontortilordning skal peke på historikkentry`() {
        flywayMigrationInTest()
        val fnr =  randomFnr()
        val oppfolginsperiodeUuid = gittBrukerUnderOppfolging(fnr)
        val kontorEndretEvent = OppfolgingsperiodeStartetNoeTilordning(Fnr(fnr.value, AKTIV), oppfolginsperiodeUuid)

        kontorTilordningService.tilordneKontor(kontorEndretEvent)
        kontorTilordningService.tilordneKontor(kontorEndretEvent)

        val (arbeidsoppfolgingskontor, historikkEntries) = transaction {
            val arbeidsoppfolgingskontor = ArbeidsOppfolgingKontorEntity[fnr.value]
            val historikkEntries = KontorHistorikkEntity
                .find { KontorhistorikkTable.ident eq fnr.value }
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
        val fnr = randomFnr()
        val oppfolginsperiodeUuid = gittBrukerUnderOppfolging(fnr)
        val aoEndring =  OppfolgingsperiodeStartetNoeTilordning(fnr, oppfolginsperiodeUuid)
        val arenaEndring = ArenaKontorFraOppfolgingsbrukerVedOppfolgingStartMedEtterslep(
            KontorTilordning(
                fnr,
                KontorId("1122"),
                oppfolginsperiodeUuid
            ),
            sistEndretIArena = OffsetDateTime.now(),
            endretAvRegistrant = System(Systemnavn.VEILARBOPPFOLGING),
        )

        kontorTilordningService.tilordneKontor(KontorEndringer(
            aoKontorEndret = aoEndring,
            arenaKontorEndret = arenaEndring,
        ))

        transaction { ArbeidsOppfolgingKontorEntity[fnr.value].kontorId } shouldBe "4154"
        transaction { ArenaKontorEntity[fnr.value].kontorId } shouldBe "1122"
    }

    @Test
    fun `skal lagre arena-kontor i arenakontor-tabell men ikke i aokontor-tabell`() {
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
            endretAvRegistrant = System(Systemnavn.VEILARBOPPFOLGING),
        )

        kontorTilordningService.tilordneKontor(KontorEndringer(
            arenaKontorEndret = arenaEndring,
        ))

        shouldThrow<EntityNotFoundException> {
            transaction { ArbeidsOppfolgingKontorEntity[fnr] }
        }
        transaction { ArenaKontorEntity[fnr].fnr.value } shouldBe fnr
    }

    @Test
    fun `skal lagre ao-kontor i aokontor-tabell`() {
        flywayMigrationInTest()
        val fnr = randomFnr()
        val oppfolginsperiodeUuid = gittBrukerUnderOppfolging(fnr)
        val aoEndring =  OppfolgingsperiodeStartetNoeTilordning(Fnr(fnr.value, AKTIV), oppfolginsperiodeUuid)

        kontorTilordningService.tilordneKontor(KontorEndringer(
            aoKontorEndret = aoEndring,
        ))

        transaction { ArbeidsOppfolgingKontorEntity[fnr.value].fnr.value } shouldBe fnr.value
    }

    @Test
    fun `skal logge fraKontorId ved flytting av veileder`() {
        flywayMigrationInTest()
        val loggedeHendelser = mutableListOf<LoggetKontorEvent>()
        val loggSattKontorEvent: LoggSattKontorEvent = { kontorId, fraKontorId, kontorEndringsType, kontorType ->
            loggedeHendelser += LoggetKontorEvent(kontorId, fraKontorId, kontorEndringsType, kontorType)
        }
        val kontorTilordningService = no.nav.services.KontorTilordningService(loggSattKontorEvent)
        val fnr = randomFnr()
        val oppfolginsperiodeUuid = gittBrukerUnderOppfolging(fnr)

        kontorTilordningService.tilordneKontor(OppfolgingsperiodeStartetNoeTilordning(fnr, oppfolginsperiodeUuid))
        kontorTilordningService.tilordneKontor(
            KontorSattAvVeileder(
                KontorTilordning(fnr, KontorId("1122"), oppfolginsperiodeUuid),
                Veileder(NavIdent("Z123456"))
            )
        )

        loggedeHendelser.last() shouldBe LoggetKontorEvent(
            kontorId = "1122",
            fraKontorId = "4154",
            kontorEndringsType = KontorEndringsType.FlyttetAvVeileder,
            kontorType = KontorTypeForBigQuery.ARBEIDSOPPFOLGINGSKONTOR,
        )
    }
}