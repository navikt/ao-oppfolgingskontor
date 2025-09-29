package kafka.consumers;

import domain.ArenaKontorUtvidet
import io.kotest.assertions.withClue
import io.kotest.matchers.types.shouldBeInstanceOf
import no.nav.db.Fnr
import no.nav.domain.KontorId
import no.nav.domain.OppfolgingsperiodeId
import no.nav.kafka.consumers.BeforeCutoff
import no.nav.kafka.consumers.EndringPaOppfolgingsBrukerProcessor
import no.nav.kafka.consumers.Feil
import no.nav.kafka.consumers.FormidlingsGruppe
import no.nav.kafka.consumers.HaddeNyereEndring
import no.nav.kafka.consumers.IkkeUnderOppfolging
import no.nav.kafka.consumers.IngenEndring
import no.nav.kafka.consumers.Kvalifiseringsgruppe
import no.nav.kafka.consumers.SkalLagre
import no.nav.kafka.consumers.UnderOppfolgingIArenaMenIkkeLokalt
import no.nav.services.AktivOppfolgingsperiode
import no.nav.services.NotUnderOppfolging
import no.nav.services.OppfolgingperiodeOppslagFeil
import no.nav.utils.randomFnr
import org.apache.kafka.streams.processor.api.Record
import org.junit.jupiter.api.Test
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
        val fnr = randomFnr()
        val oppfolgingsperiode = OppfolgingsperiodeId(UUID.randomUUID())
        val processor = EndringPaOppfolgingsBrukerProcessor(
            { AktivOppfolgingsperiode(fnr, oppfolgingsperiode, OffsetDateTime.now().minusDays(2)) },
            { arenaKontor() })
        val result = processor.internalProcess(testRecord(fnr, sistEndretDato = rettFørCutoff))
        withClue("forventer BeforeCutoff men var ${result.javaClass.simpleName}") {
            result.shouldBeInstanceOf<BeforeCutoff>()
        }
    }

    @Test
    fun `skal ikke cutte off når tidspunkt er for 13 aug 2025`() {
        val fnr = randomFnr()
        val oppfolgingsperiode = OppfolgingsperiodeId(UUID.randomUUID())
        val processor = EndringPaOppfolgingsBrukerProcessor(
            { AktivOppfolgingsperiode(fnr, oppfolgingsperiode, OffsetDateTime.now().minusDays(2)) },
            { arenaKontor(endret = etterCutoffMenAnnenTidssone.minusSeconds(1)) })
        val result = processor.internalProcess(testRecord(fnr, sistEndretDato = etterCutoffMenAnnenTidssone))
        withClue("forventer SkalLagre men var ${result.javaClass.simpleName}") {
            result.shouldBeInstanceOf<SkalLagre>()
        }
    }

    @Test
    fun `skal ikke behandle melding hvis bruker ikke er under oppfølging`() {
        val fnr = randomFnr()
        val processor = EndringPaOppfolgingsBrukerProcessor(
            { NotUnderOppfolging },
            { arenaKontor(etterCutoffMenAnnenTidssone) })
        val result = processor.internalProcess(testRecord(fnr, sistEndretDato = etterCutoffMenAnnenTidssone, formidlingsGruppe = FormidlingsGruppe.ISERV))
        withClue("forventer IkkeUnderOppfolging men var ${result.javaClass.simpleName}") {
            result.shouldBeInstanceOf<IkkeUnderOppfolging>()
        }
    }


    @Test
    fun `skal behandle melding selvom bruker ikke har oppfølgingsperiode hvis hen er under oppfølging i arena`() {
        val fnr = randomFnr()
        val processor = EndringPaOppfolgingsBrukerProcessor(
            { NotUnderOppfolging },
            { arenaKontorFørCutoff() })
        val result = processor.internalProcess(testRecord(fnr, sistEndretDato = etterCutoffMenAnnenTidssone, formidlingsGruppe = FormidlingsGruppe.ARBS))
        withClue("forventer UnderOppfolgingIArenaMenIkkeLokalt men var ${result.javaClass.simpleName}") {
            result.shouldBeInstanceOf<UnderOppfolgingIArenaMenIkkeLokalt>()
        }
    }

    @Test
    fun `skal lagre melding hvis lagret kontor-endring er eldre enn innkommende endring`() {
        val sisteLagreMeldingTidspunkt = rettFørCutoff.plusDays(1)
        val innkommendeMeldingEndretTidspunkt = sisteLagreMeldingTidspunkt.plusSeconds(1)
        val oppfolgingsStartet = sisteLagreMeldingTidspunkt.minusDays(1)
        val oppfolgingsperiodeId = OppfolgingsperiodeId(UUID.randomUUID())
        val fnr = randomFnr()
        val processor = EndringPaOppfolgingsBrukerProcessor(
            { AktivOppfolgingsperiode(fnr, oppfolgingsperiodeId, oppfolgingsStartet) },
            { arenaKontor(endret = sisteLagreMeldingTidspunkt) })

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
        val fnr = randomFnr()
        val processor = EndringPaOppfolgingsBrukerProcessor(
            { AktivOppfolgingsperiode(fnr, oppfolgingsperiodeId, oppfolgingsStartet) },
            { arenaKontor( sisteLagreMeldingTidspunkt) })

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
        val kontorId = "3333"
        val processor = EndringPaOppfolgingsBrukerProcessor(
            { AktivOppfolgingsperiode(fnr, oppfolgingsperiodeId, oppfolgingStartet) },
            { arenaKontor(kontor = kontorId, endret = oppfolgingStartet, oppfolgingsperiodeId = oppfolgingsperiodeId) })

        val result = processor.internalProcess(testRecord(fnr, enhet = kontorId))

        result.shouldBeInstanceOf<IngenEndring>()
    }

    @Test
    fun `skal lagre samme arena-kontor i ny periode hvis perioden er ny`() {
        val oppfolgingStartet = OffsetDateTime.now().minusDays(1)
        val oppfolgingsperiodeId = OppfolgingsperiodeId(UUID.randomUUID())
        val fnr = randomFnr()
        val kontorId = "3333"
        val arenaKontorMedAnnenOppfolgingsperiode = arenaKontor(kontor = kontorId, endret = oppfolgingStartet)
        val processor = EndringPaOppfolgingsBrukerProcessor(
            { AktivOppfolgingsperiode(fnr, oppfolgingsperiodeId, oppfolgingStartet) },
            { arenaKontorMedAnnenOppfolgingsperiode })

        val result = processor.internalProcess(testRecord(fnr, enhet = kontorId))

        result.shouldBeInstanceOf<SkalLagre>()
    }

    @Test
    fun `skal håndtere feil med perioder`() {
        val fnr = randomFnr()
        val processor = EndringPaOppfolgingsBrukerProcessor(
            { OppfolgingperiodeOppslagFeil("Feil med perioder!?") },
            { null })
        val result = processor.internalProcess(testRecord(fnr, sistEndretDato = etterCutoffMenAnnenTidssone))
        withClue("forventer Feil men var ${result.javaClass.simpleName}") {
            result.shouldBeInstanceOf<Feil>()
        }
    }

    fun testRecord(
        fnr: Fnr,
        enhet: String = "4414",
        sistEndretDato: OffsetDateTime = OffsetDateTime.now(),
        formidlingsGruppe: FormidlingsGruppe = FormidlingsGruppe.ARBS,
        kvalifiseringsgruppe: Kvalifiseringsgruppe = Kvalifiseringsgruppe.BATT
    ): Record<String, String> {
        return TopicUtils.endringPaaOppfolgingsBrukerMessage(
            fnr,
            enhet,
            sistEndretDato,
            formidlingsGruppe,
            kvalifiseringsgruppe
        )
    }

    fun arenaKontor(
        endret: OffsetDateTime = OffsetDateTime.now(),
        kontor: String = "4111",
        oppfolgingsperiodeId: OppfolgingsperiodeId? = OppfolgingsperiodeId(UUID.randomUUID()),
    ): ArenaKontorUtvidet {
        return ArenaKontorUtvidet(
            kontorId = KontorId(kontor),
            oppfolgingsperiodeId = oppfolgingsperiodeId,
            sistEndretDatoArena = endret
        )
    }
    fun arenaKontorFørCutoff() = arenaKontor(endret = rettFørCutoff)
}
