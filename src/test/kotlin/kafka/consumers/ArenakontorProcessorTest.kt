package kafka.consumers

import domain.ArenaKontorUtvidet
import http.client.ArenakontorFunnet
import http.client.ArenakontorIkkeFunnet
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import no.nav.db.Fnr
import no.nav.db.Ident
import no.nav.db.entity.ArenaKontorEntity
import no.nav.domain.KontorId
import no.nav.domain.OppfolgingsperiodeId
import no.nav.domain.externalEvents.OppfolgingsperiodeAvsluttet
import no.nav.domain.externalEvents.OppfolgingsperiodeEndret
import no.nav.domain.externalEvents.OppfolgingsperiodeStartet
import no.nav.kafka.processor.Commit
import no.nav.kafka.processor.Skip
import no.nav.services.KontorTilordningService
import no.nav.utils.bigQueryClient
import no.nav.utils.flywayMigrationInTest
import no.nav.utils.kontorTilordningService
import no.nav.utils.randomFnr
import org.apache.kafka.streams.processor.api.Record
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.util.*


class ArenakontorProcessorTest {

    companion object {
        @BeforeAll
        @JvmStatic
        fun setup() {
            flywayMigrationInTest()
        }
    }

    @Test
    fun `Skal ignorere OppfolgingsperiodeAvsluttet-hendelser`() {
        val processor = ArenakontorVedOppfolgingStartetProcessor(
            { ArenakontorIkkeFunnet() },
            { kontorTilordningService.tilordneKontor(it, true) },
            { null },
            { Result.success(Unit) },
            true
        )
        val record = oppfolgingsperiodeAvsluttetRecord()
        val result = processor.process(record)
        result.shouldBeInstanceOf<Skip<*, *>>()
        transaction {
            ArenaKontorEntity.findById(record.value().fnr.value) shouldBe null
        }
    }

    @Test
    fun `Skal lagre arenakontor for funnet FNR`() {
        val record = oppfolgingsperiodeStartetRecord()
        val kontorId = KontorId("1234")
        val gammelKontorId = KontorId("4321")
        val processor = ArenakontorVedOppfolgingStartetProcessor(
            { ArenakontorFunnet(kontorId, ZonedDateTime.now()) },
            { kontorTilordningService.tilordneKontor(it, true) },
            {
                ArenaKontorUtvidet(
                    kontorId = gammelKontorId,
                    oppfolgingsperiodeId = null,
                    sistEndretDatoArena = OffsetDateTime.now().minusDays(1)
                )
            },
            { Result.success(Unit) },
            true
        )
        val result = processor.process(record)
        result.shouldBeInstanceOf<Commit<*, *>>()
        transaction {
            val arenaKontorId = ArenaKontorEntity.findById(record.value().fnr.value)!!.kontorId
            arenaKontorId shouldBe kontorId.id
        }
    }

    @Test
    fun `Skal ikke lagre arenakontor når det ikke har vært en endring`() {
        val record = oppfolgingsperiodeStartetRecord()
        val kontorId = KontorId("1234")
        val gammelKontorId = KontorId("1234")
        val processor = `ArenakontorVedOppfolgingStartetProcessor`(
            { ArenakontorFunnet(kontorId, ZonedDateTime.now()) },
            { throw Exception("Skal ikke lagre når det ikke har vært en endring") },
            {
                ArenaKontorUtvidet(
                    kontorId = gammelKontorId,
                    oppfolgingsperiodeId = null,
                    sistEndretDatoArena = OffsetDateTime.now().minusDays(1)
                )
            },
            { Result.success(Unit) },
            true
        )
        val result = processor.process(record)
        result.shouldBeInstanceOf<Skip<*, *>>()
    }

    @Test
    fun `Skal ikke lagre arenakontor når vi har lagret kontor med nyere timestamp `() {
        val record = oppfolgingsperiodeStartetRecord()
        val kontorIdHentetSynkront = KontorId("1234")
        val nyereKontorId = KontorId("4321")
        val tidspunktSynkronHenting = ZonedDateTime.now().minusSeconds(30)
        val tidspunktKontorIdIDatabasen = OffsetDateTime.now().minusSeconds(20)
        val processor = ArenakontorVedOppfolgingStartetProcessor(
            { ArenakontorFunnet(kontorIdHentetSynkront, tidspunktSynkronHenting) },
            { throw Exception("Skal ikke lagre når det ikke har vært en endring") },
            {
                ArenaKontorUtvidet(
                    kontorId = nyereKontorId,
                    oppfolgingsperiodeId = null,
                    sistEndretDatoArena = tidspunktKontorIdIDatabasen
                )
            },
            { Result.success(Unit) },
            true
        )
        val result = processor.process(record)
        result.shouldBeInstanceOf<Skip<*, *>>()
    }

    @Test
    fun `Skal returnere commit selv om vi ikke finner arenakontor innkommen FNR`() {
        val record = oppfolgingsperiodeStartetRecord()
        val processor = `ArenakontorVedOppfolgingStartetProcessor`(
            { ArenakontorIkkeFunnet() },
            { kontorTilordningService.tilordneKontor(it, true) },
            { null },
            { Result.success(Unit) },
            true
        )
        val result = processor.process(record)
        result.shouldBeInstanceOf<Commit<*, *>>()
        transaction {
            ArenaKontorEntity.findById(record.value().fnr.value) shouldBe null
        }
    }

    @Test
    fun `Skal publisere melding på topic når PUBLISER_ARENA_KONTOR er true`() {
        val record = oppfolgingsperiodeStartetRecord()
        val kontorId = KontorId("1234")
        val gammelKontorId = KontorId("4321")
        var harPublisertMelding = false
        val processor = ArenakontorVedOppfolgingStartetProcessor(
            { ArenakontorFunnet(kontorId, ZonedDateTime.now()) },
            { kontorTilordningService.tilordneKontor(it, true) },
            {
                ArenaKontorUtvidet(
                    kontorId = gammelKontorId,
                    oppfolgingsperiodeId = null,
                    sistEndretDatoArena = OffsetDateTime.now().minusDays(1)
                )
            },
            {
                harPublisertMelding = true
                Result.success(Unit)
            },
            publiserArenaKontor = true
        )
        val result = processor.process(record)
        result.shouldBeInstanceOf<Commit<*, *>>()
        harPublisertMelding shouldBe true
    }

    @Test
    fun `Skal ikke publisere melding på topic når PUBLISER_ARENA_KONTOR er false`() {
        val record = oppfolgingsperiodeStartetRecord()
        val kontorId = KontorId("1234")
        val gammelKontorId = KontorId("4321")
        var harPublisertMelding = false
        val processor = ArenakontorVedOppfolgingStartetProcessor(
            { ArenakontorFunnet(kontorId, ZonedDateTime.now()) },
            { kontorTilordningService.tilordneKontor(it, true) },
            {
                ArenaKontorUtvidet(
                    kontorId = gammelKontorId,
                    oppfolgingsperiodeId = null,
                    sistEndretDatoArena = OffsetDateTime.now().minusDays(1)
                )
            },
            {
                harPublisertMelding = true
                Result.success(Unit)
            },
            publiserArenaKontor = false
        )
        val result = processor.process(record)
        result.shouldBeInstanceOf<Commit<*, *>>()
        harPublisertMelding shouldBe false
    }

    fun oppfolgingsperiodeAvsluttetRecord(): Record<Ident, OppfolgingsperiodeEndret> {
        val oppfolgingsperiode = oppfolgingsperiodeAvsluttet()
        return Record(
            oppfolgingsperiode.fnr as Ident,
            oppfolgingsperiode,
            Instant.now().toEpochMilli(),
        )
    }

    fun oppfolgingsperiodeStartetRecord(oppfolgingsperiode: OppfolgingsperiodeStartet = oppfolgingsperiodeStartet()): Record<Ident, OppfolgingsperiodeEndret> {
        return Record(
            oppfolgingsperiode.fnr as Ident,
            oppfolgingsperiode,
            Instant.now().toEpochMilli(),
        )
    }

    fun oppfolgingsperiodeStartet(fnr: Fnr = randomFnr()): OppfolgingsperiodeStartet {
        return OppfolgingsperiodeStartet(
            fnr,
            ZonedDateTime.now(),
            OppfolgingsperiodeId(UUID.randomUUID()),
            true,
            null,
            null
        )
    }

    fun oppfolgingsperiodeAvsluttet(): OppfolgingsperiodeAvsluttet {
        return OppfolgingsperiodeAvsluttet(
            randomFnr(),
            ZonedDateTime.now(),
            OppfolgingsperiodeId(UUID.randomUUID())
        )
    }

}