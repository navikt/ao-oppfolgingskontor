package kafka.consumers;

import domain.ArenaKontorUtvidet
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.Called
import io.mockk.mockk
import io.mockk.verify
import no.nav.db.Fnr
import no.nav.domain.KontorId
import no.nav.domain.OppfolgingsperiodeId
import no.nav.domain.events.KontorEndretEvent
import no.nav.kafka.consumers.*
import no.nav.services.AktivOppfolgingsperiode
import no.nav.services.KontorTilordningService
import no.nav.services.NotUnderOppfolging
import no.nav.services.OppfolgingperiodeOppslagFeil
import no.nav.utils.randomFnr
import org.apache.kafka.streams.processor.api.Record
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.*

class EndringPaOppfolgingsBrukerProcessorTest {
    val KontorTilordningServiceMock = mockk<KontorTilordningService>()

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
        val fnr = randomFnr()
        val oppfolgingsperiode = OppfolgingsperiodeId(UUID.randomUUID())
        val brukAoRuting = false
        val processor = EndringPaOppfolgingsBrukerProcessor(
            { AktivOppfolgingsperiode(fnr, oppfolgingsperiode, OffsetDateTime.now().minusDays(2)) },
            { arenaKontor() },
            { KontorTilordningServiceMock.tilordneKontor(it, brukAoRuting) },
            { Result.success(Unit) }
        )

        val result = processor.internalProcess(testRecord(fnr, sistEndretDato = rettFørCutoff))

        verify { KontorTilordningServiceMock wasNot Called }
        withClue("forventer BeforeCutoff men var ${result.javaClass.simpleName}") {
            result.shouldBeInstanceOf<BeforeCutoff>()
        }
    }

    @Test
    fun `skal ikke cutte off når tidspunkt er for 13 aug 2025`() {
        val fnr = randomFnr()
        val oppfolgingsperiode = OppfolgingsperiodeId(UUID.randomUUID())
        val brukAoRuting = false
        val processor = EndringPaOppfolgingsBrukerProcessor(
            { AktivOppfolgingsperiode(fnr, oppfolgingsperiode, OffsetDateTime.now().minusDays(2)) },
            { arenaKontor(endret = etterCutoffMenAnnenTidssone.minusSeconds(1)) },
            { KontorTilordningServiceMock.tilordneKontor(it, brukAoRuting) },
            { Result.success(Unit) }
        )
        val result = processor.internalProcess(testRecord(fnr, sistEndretDato = etterCutoffMenAnnenTidssone))
        withClue("forventer SkalLagre men var ${result.javaClass.simpleName}") {
            result.shouldBeInstanceOf<SkalLagre>()
            result.erFørsteArenaKontorIOppfolgingsperiode shouldBe true
        }
    }

    @Test
    fun `skal ikke behandle melding når bruker ikke har oppfølgingsperiode lagret`() {
        val fnr = randomFnr()
        val brukAoRuting = false
        val processor = EndringPaOppfolgingsBrukerProcessor(
            { NotUnderOppfolging },
            { arenaKontorFørCutoff() },
            { KontorTilordningServiceMock.tilordneKontor(it, brukAoRuting) },
            { Result.success(Unit) }
        )
        val result = processor.internalProcess(
            testRecord(
                fnr,
                sistEndretDato = etterCutoffMenAnnenTidssone,
                formidlingsGruppe = FormidlingsGruppe.ARBS
            )
        )
        withClue("forventer UnderOppfolgingIArenaMenIkkeLokalt men var ${result.javaClass.simpleName}") {
            result.shouldBeInstanceOf<IkkeUnderOppfølging>()
        }
    }

    @Test
    fun `skal lagre arena-kontor hvis lagret arena-kontor er eldre enn innkommende endring`() {
        val sisteLagreMeldingTidspunkt = rettFørCutoff.plusDays(1)
        val innkommendeMeldingEndretTidspunkt = sisteLagreMeldingTidspunkt.plusSeconds(1)
        val oppfolgingsStartet = sisteLagreMeldingTidspunkt.minusDays(1)
        val oppfolgingsperiodeId = OppfolgingsperiodeId(UUID.randomUUID())
        val kontorId = KontorId("3131")
        val fnr = randomFnr()
        val processor = EndringPaOppfolgingsBrukerProcessor(
            { AktivOppfolgingsperiode(fnr, oppfolgingsperiodeId, oppfolgingsStartet) },
            {
                arenaKontor(
                    endret = sisteLagreMeldingTidspunkt,
                    kontor = kontorId,
                    oppfolgingsperiodeId = oppfolgingsperiodeId
                )
            },
            {},
            { Result.success(Unit) }
        )

        val result = processor.internalProcess(
            testRecord(
                fnr,
                sistEndretDato = innkommendeMeldingEndretTidspunkt,
                kontor = KontorId("3132")
            )
        )

        withClue("forventer SkalLagre men var ${result.javaClass.simpleName}") {
            result.shouldBeInstanceOf<SkalLagre>()
            result.erFørsteArenaKontorIOppfolgingsperiode shouldBe false
        }
    }

    @Test
    fun `skal ikke behandle melding hvis vi har nyere endring lagret allerede`() {
        val sisteLagreMeldingTidspunkt = rettFørCutoff.plusDays(1)
        val innkommendeMeldingEndretTidspunkt = sisteLagreMeldingTidspunkt.minusSeconds(1)
        val oppfolgingsStartet = sisteLagreMeldingTidspunkt.minusDays(1)
        val oppfolgingsperiodeId = OppfolgingsperiodeId(UUID.randomUUID())
        val fnr = randomFnr()
        val processor = EndringPaOppfolgingsBrukerProcessor(
            { AktivOppfolgingsperiode(fnr, oppfolgingsperiodeId, oppfolgingsStartet) },
            { arenaKontor(sisteLagreMeldingTidspunkt) },
            {},
            { Result.success(Unit) }
        )

        val result = processor.internalProcess(testRecord(fnr, sistEndretDato = innkommendeMeldingEndretTidspunkt))

        withClue("forventer HaddeNyereEndring men var ${result.javaClass.simpleName}") {
            result.shouldBeInstanceOf<HaddeNyereEndring>()
        }
    }

    @Test
    fun `skal ikke behandle melding hvis arenakontor ikke har endret seg`() {
        val oppfolgingStartet = OffsetDateTime.now().minusDays(1)
        val oppfolgingsperiodeId = OppfolgingsperiodeId(UUID.randomUUID())
        val fnr = randomFnr()
        val kontorId = KontorId("3333")
        val processor = EndringPaOppfolgingsBrukerProcessor(
            { AktivOppfolgingsperiode(fnr, oppfolgingsperiodeId, oppfolgingStartet) },
            { arenaKontor(kontor = kontorId, endret = oppfolgingStartet, oppfolgingsperiodeId = oppfolgingsperiodeId) },
            {},
            { Result.success(Unit) }
        )

        val result = processor.internalProcess(testRecord(fnr, kontor = kontorId))

        result.shouldBeInstanceOf<IngenEndring>()
    }

    @Test
    fun `skal lagre samme arena-kontor i ny periode hvis perioden er ny`() {
        val oppfolgingStartet = OffsetDateTime.now().minusDays(1)
        val oppfolgingsperiodeId = OppfolgingsperiodeId(UUID.randomUUID())
        val fnr = randomFnr()
        val kontorId = KontorId("3333")
        val arenaKontorMedAnnenOppfolgingsperiode = arenaKontor(kontor = kontorId, endret = oppfolgingStartet)
        val processor = EndringPaOppfolgingsBrukerProcessor(
            { AktivOppfolgingsperiode(fnr, oppfolgingsperiodeId, oppfolgingStartet) },
            { arenaKontorMedAnnenOppfolgingsperiode },
            {},
            { Result.success(Unit) }
        )

        val result = processor.internalProcess(testRecord(fnr, kontor = kontorId))

        result.shouldBeInstanceOf<SkalLagre>()
        result.erFørsteArenaKontorIOppfolgingsperiode shouldBe true
    }

    @Test
    fun `skal håndtere feil med perioder`() {
        val fnr = randomFnr()
        val processor = EndringPaOppfolgingsBrukerProcessor(
            { OppfolgingperiodeOppslagFeil("Feil med perioder!?") },
            { null },
            {},
            { Result.success(Unit) }
        )
        val result = processor.internalProcess(testRecord(fnr, sistEndretDato = etterCutoffMenAnnenTidssone))
        withClue("forventer Feil men var ${result.javaClass.simpleName}") {
            result.shouldBeInstanceOf<Feil>()
        }
    }

    @Test
    fun `harKontorBlittEndret - skal gi IKKE_ENDRET_KONTOR hvis kontor og oppfolgingsperiode er lik`() {
        val oppfolgingsperiodeId = OppfolgingsperiodeId(UUID.randomUUID())
        harKontorBlittEndret(
            ArenaKontorUtvidet(KontorId("1212"), oppfolgingsperiodeId, OffsetDateTime.now()),
            "1212",
            oppfolgingsperiodeId,
        ) shouldBe ArenaKontorEndringsType.IKKE_ENDRET_KONTOR
    }

    @Test
    fun `harKontorBlittEndret - skal gi FØRSTE_KONTOR_PÅ_BRUKER hvis kontor det ikke finnes tidligere arenakontor på bruker`() {
        val oppfolgingsperiodeId = OppfolgingsperiodeId(UUID.randomUUID())
        harKontorBlittEndret(
            null,
            "1212",
            oppfolgingsperiodeId,
        ) shouldBe ArenaKontorEndringsType.FØRSTE_KONTOR_PÅ_BRUKER
    }

    @Test
    fun `harKontorBlittEndret - skal gi FØRSTE_KONTOR_I_PERIODE hvis kontor er likt men oppfolgingsperiode er forskjellig`() {
        harKontorBlittEndret(
            ArenaKontorUtvidet(KontorId("1212"), OppfolgingsperiodeId(UUID.randomUUID()), OffsetDateTime.now()),
            "1212",
            OppfolgingsperiodeId(UUID.randomUUID()),
        ) shouldBe ArenaKontorEndringsType.FØRSTE_KONTOR_I_PERIODE
    }

    @Test
    fun `harKontorBlittEndret - skal gi ENDRET_I_PERIODE hvis oppfolgingsperiode er lik men kontor er forskjellig`() {
        val oppfolgingsperiodeId = OppfolgingsperiodeId(UUID.randomUUID())
        harKontorBlittEndret(
            ArenaKontorUtvidet(KontorId("1212"), oppfolgingsperiodeId, OffsetDateTime.now()),
            "1213",
            oppfolgingsperiodeId,
        ) shouldBe ArenaKontorEndringsType.ENDRET_I_PERIODE
    }

    @Test
    fun `skal publisere melding ut hvis publiserArenaKontor er true`() {
        val fnr = randomFnr()
        val oppfolgingsperiode = OppfolgingsperiodeId(UUID.randomUUID())
        var harPublisertMelding = false
        val processor = EndringPaOppfolgingsBrukerProcessor(
            { AktivOppfolgingsperiode(fnr, oppfolgingsperiode, OffsetDateTime.now().minusDays(2)) },
            { arenaKontor(endret = etterCutoffMenAnnenTidssone.minusSeconds(1)) },
            {},
            {
                harPublisertMelding = true
                Result.success(Unit)
            },
            publiserArenaKontor = true
        )

        processor.process(testRecord(fnr, sistEndretDato = etterCutoffMenAnnenTidssone))

        harPublisertMelding shouldBe true
    }

    @Test
    fun `skal ikke publisere melding ut hvis publiserArenaKontor er false`() {
        val fnr = randomFnr()
        val oppfolgingsperiode = OppfolgingsperiodeId(UUID.randomUUID())
        var harPublisertMelding = false
        val processor = EndringPaOppfolgingsBrukerProcessor(
            { AktivOppfolgingsperiode(fnr, oppfolgingsperiode, OffsetDateTime.now().minusDays(2)) },
            { arenaKontor(endret = etterCutoffMenAnnenTidssone.minusSeconds(1)) },
            {},
            {
                harPublisertMelding = true
                Result.success(Unit)
            },
            publiserArenaKontor = false
        )

        processor.process(testRecord(fnr, sistEndretDato = etterCutoffMenAnnenTidssone))

        harPublisertMelding shouldBe false
    }

    fun testRecord(
        fnr: Fnr,
        kontor: KontorId = KontorId("4414"),
        sistEndretDato: OffsetDateTime = OffsetDateTime.now(),
        formidlingsGruppe: FormidlingsGruppe = FormidlingsGruppe.ARBS,
        kvalifiseringsgruppe: Kvalifiseringsgruppe = Kvalifiseringsgruppe.BATT
    ): Record<String, String> {
        return TopicUtils.endringPaaOppfolgingsBrukerMessage(
            fnr,
            kontor.id,
            sistEndretDato,
            formidlingsGruppe,
            kvalifiseringsgruppe
        )
    }

    fun arenaKontor(
        endret: OffsetDateTime = OffsetDateTime.now(),
        kontor: KontorId = KontorId("4111"),
        oppfolgingsperiodeId: OppfolgingsperiodeId? = OppfolgingsperiodeId(UUID.randomUUID()),
    ): ArenaKontorUtvidet {
        return ArenaKontorUtvidet(
            kontorId = kontor,
            oppfolgingsperiodeId = oppfolgingsperiodeId,
            sistEndretDatoArena = endret
        )
    }

    fun arenaKontorFørCutoff() = arenaKontor(endret = rettFørCutoff)
}
