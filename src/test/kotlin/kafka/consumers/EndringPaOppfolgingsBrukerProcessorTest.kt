package kafka.consumers;

import io.kotest.assertions.withClue
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import no.nav.db.Fnr
import no.nav.db.entity.ArenaKontorEntity
import no.nav.db.table.ArenaKontorTable
import no.nav.domain.OppfolgingsperiodeId
import no.nav.kafka.consumers.BeforeCutoff
import no.nav.kafka.consumers.EndringPaOppfolgingsBrukerProcessor
import no.nav.kafka.consumers.Feil
import no.nav.kafka.consumers.HaddeNyereEndring
import no.nav.kafka.consumers.IkkeUnderOppfolging
import no.nav.kafka.consumers.IngenEndring
import no.nav.kafka.consumers.SkalLagre
import no.nav.services.AktivOppfolgingsperiode
import no.nav.services.NotUnderOppfolging
import no.nav.services.OppfolgingperiodeOppslagFeil
import org.apache.kafka.streams.processor.api.Record
import org.jetbrains.exposed.dao.DaoEntityID
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class EndringPaOppfolgingsBrukerProcessorTest {
    val rettFørCutoff = OffsetDateTime.of(
        2025,
        8,
        12,
        23,
        59,
        59,
        0,
        ZoneOffset.ofHours(0)
    )
    val etterCutoffMenAnnenTidssone = OffsetDateTime.of(
        2025,
        8,
        12,
        23,
        59,
        59,
        0,
        ZoneOffset.ofHours(-1)
    )

    @Test
    fun `skal cutte off når tidspunkt er før 13 aug 2025 (i annen tidssone)`() {
        val fnr = Fnr("12081344844")
        val oppfolgingsperiode = OppfolgingsperiodeId(UUID.randomUUID())
        val processor = EndringPaOppfolgingsBrukerProcessor(
            { null },
            { AktivOppfolgingsperiode(fnr, oppfolgingsperiode, OffsetDateTime.now().minusDays(2)) })
        val result = processor.internalProcess(testRecord(fnr, sistEndretDato = rettFørCutoff))
        withClue("forventer BeforeCutoff men var ${result.javaClass.simpleName}") {
            result.shouldBeInstanceOf<BeforeCutoff>()
        }
    }

    @Test
    fun `skal ikke cutte off når tidspunkt er for 13 aug 2025`() {
        val fnr = Fnr("12081344844")
        val oppfolgingsperiode = OppfolgingsperiodeId(UUID.randomUUID())
        val processor = EndringPaOppfolgingsBrukerProcessor(
            { null },
            { AktivOppfolgingsperiode(fnr, oppfolgingsperiode, OffsetDateTime.now().minusDays(2)) })
        val result = processor.internalProcess(testRecord(fnr, sistEndretDato = etterCutoffMenAnnenTidssone))
        withClue("forventer SkalLagre men var ${result.javaClass.simpleName}") {
            result.shouldBeInstanceOf<SkalLagre>()
        }
    }

    @Test
    fun `skal ikke behandle melding hvis bruker ikke er under oppfølging`() {
        val fnr = Fnr("12081344844")
        val processor = EndringPaOppfolgingsBrukerProcessor(
            { null },
            { NotUnderOppfolging })
        val result = processor.internalProcess(testRecord(fnr, sistEndretDato = etterCutoffMenAnnenTidssone))
        withClue("forventer IkkeUnderOppfolging men var ${result.javaClass.simpleName}") {
            result.shouldBeInstanceOf<IkkeUnderOppfolging>()
        }
    }

    @Test
    fun `skal lagre melding hvis lagret kontor-endring er eldre enn innkommende endring`() {
        val sisteLagreMeldingTidspunkt = rettFørCutoff.plusDays(1)
        val innkommendeMeldingEndretTidspunkt = sisteLagreMeldingTidspunkt.plusSeconds(1)
        val oppfolgingsStartet = sisteLagreMeldingTidspunkt.minusDays(1)
        val oppfolgingsperiodeId = OppfolgingsperiodeId(UUID.randomUUID())
        val fnr = Fnr("12081344844")
        val processor = EndringPaOppfolgingsBrukerProcessor(
            { arenaKontor(fnr, sisteLagreMeldingTidspunkt) },
            { AktivOppfolgingsperiode(fnr, oppfolgingsperiodeId, oppfolgingsStartet) })

        val result = processor.internalProcess(testRecord(fnr, sistEndretDato = innkommendeMeldingEndretTidspunkt))

        withClue("forventer SkalLagre men var ${result.javaClass.simpleName}") {
            result.shouldBeInstanceOf<SkalLagre>()
        }
    }

    @Test
    fun `skal ikke behandle melding hvis vi har nyere endring lagret allerede`() {
        val sisteLagreMeldingTidspunkt = rettFørCutoff.plusDays(1)
        val innkommendeMeldingEndretTidspunkt = sisteLagreMeldingTidspunkt.minusSeconds(1)
        val oppfolgingsStartet = sisteLagreMeldingTidspunkt.minusDays(1)
        val oppfolgingsperiodeId = OppfolgingsperiodeId(UUID.randomUUID())
        val fnr = Fnr("12081344844")
        val processor = EndringPaOppfolgingsBrukerProcessor(
            { arenaKontor(fnr, sisteLagreMeldingTidspunkt) },
            { AktivOppfolgingsperiode(fnr, oppfolgingsperiodeId, oppfolgingsStartet) })

        val result = processor.internalProcess(testRecord(fnr, sistEndretDato = innkommendeMeldingEndretTidspunkt))

        withClue("forventer HaddeNyereEndring men var ${result.javaClass.simpleName}") {
            result.shouldBeInstanceOf<HaddeNyereEndring>()
        }
    }

    @Test
    fun `skal ikke behandle melding hvis arenakontor ikke har endret seg`() {
        val oppfolgingsStartet = OffsetDateTime.now().minusDays(1)
        val oppfolgingsperiodeId = OppfolgingsperiodeId(UUID.randomUUID())
        val fnr = Fnr("12081344844")
        val kontorId = "3333"
        val processor = EndringPaOppfolgingsBrukerProcessor(
            { arenaKontor(fnr, kontor = kontorId, endret = oppfolgingsStartet) },
            { AktivOppfolgingsperiode(fnr, oppfolgingsperiodeId, oppfolgingsStartet) })

        val result = processor.internalProcess(testRecord(fnr, enhet = kontorId))

        result.shouldBeInstanceOf<IngenEndring>()
    }

    @Test
    fun `skal håndtere feil med perioder`() {
        val fnr = Fnr("12081344844")
        val processor = EndringPaOppfolgingsBrukerProcessor(
            { null },
            { OppfolgingperiodeOppslagFeil("Feil med perioder!?") })
        val result = processor.internalProcess(testRecord(fnr, sistEndretDato = etterCutoffMenAnnenTidssone))
        withClue("forventer Feil men var ${result.javaClass.simpleName}") {
            result.shouldBeInstanceOf<Feil>()
        }
    }

    fun testRecord(fnr: Fnr, enhet: String = "4414", sistEndretDato: OffsetDateTime = OffsetDateTime.now()): Record<String, String> {
        return Record(
            fnr.value,
            """{
              "oppfolgingsenhet": "$enhet",
              "sistEndretDato": "$sistEndretDato"
            }""".trimMargin(),
            Instant.now().toEpochMilli()
        )
    }

    fun arenaKontor(fnr: Fnr, endret: OffsetDateTime = OffsetDateTime.now(), kontor: String = "4111"): ArenaKontorEntity {
        val entityId = DaoEntityID(fnr.value, ArenaKontorTable)
        val arenaKontorEntity = mockk<ArenaKontorEntity> {
            every { id } returns entityId
            every { createdAt } returns OffsetDateTime.now()
            every { updatedAt } returns OffsetDateTime.now()
            every { sistEndretDatoArena } returns endret
            every { kontorId } returns kontor
        }
        return arenaKontorEntity
    }
}
