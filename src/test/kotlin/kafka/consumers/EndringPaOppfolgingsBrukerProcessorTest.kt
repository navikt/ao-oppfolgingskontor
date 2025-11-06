package kafka.consumers;

import domain.ArenaKontorUtvidet
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import no.nav.db.Fnr
import no.nav.domain.KontorId
import no.nav.domain.OppfolgingsperiodeId
import no.nav.kafka.consumers.ArenaKontorEndringsType
import no.nav.kafka.consumers.BeforeCutoff
import no.nav.kafka.consumers.EndringPaOppfolgingsBrukerProcessor
import no.nav.kafka.consumers.Feil
import no.nav.kafka.consumers.FormidlingsGruppe
import no.nav.kafka.consumers.HaddeNyereEndring
import no.nav.kafka.consumers.IngenEndring
import no.nav.kafka.consumers.Kvalifiseringsgruppe
import no.nav.kafka.consumers.MeldingManglerEnhet
import no.nav.kafka.consumers.SkalLagre
import no.nav.kafka.consumers.SkalKanskjeUnderOppfolging
import no.nav.kafka.consumers.harKontorBlittEndret
import no.nav.kafka.processor.Retry
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
            { arenaKontor() },
            {}
        )
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
            { arenaKontor(endret = etterCutoffMenAnnenTidssone.minusSeconds(1)) }, {})
        val result = processor.internalProcess(testRecord(fnr, sistEndretDato = etterCutoffMenAnnenTidssone))
        withClue("forventer SkalLagre men var ${result.javaClass.simpleName}") {
            result.shouldBeInstanceOf<SkalLagre>()
            result.erFørsteArenaKontorIOppfolgingsperiode shouldBe true
        }
    }

    @Test
    fun `skal behandle melding selvom bruker ikke har oppfølgingsperiode fordi vi kanskje ikke vet om den ennå`() {
        val fnr = randomFnr()
        val processor = EndringPaOppfolgingsBrukerProcessor(
            { NotUnderOppfolging },
            { arenaKontorFørCutoff() },
            { }
        )
        val result = processor.internalProcess(
            testRecord(
                fnr,
                sistEndretDato = etterCutoffMenAnnenTidssone,
                formidlingsGruppe = FormidlingsGruppe.ARBS
            )
        )
        withClue("forventer UnderOppfolgingIArenaMenIkkeLokalt men var ${result.javaClass.simpleName}") {
            result.shouldBeInstanceOf<SkalKanskjeUnderOppfolging>()
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
            {})

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
            {})

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
            {})

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
            {})

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
            {})
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
    fun `Skal lagre tidligArenaKontor hvis bruker kanskje skal under oppfølging`() {
        var harKaltPåLagreTidligArenaKontor = false
        val fnr = randomFnr()
        val processor = EndringPaOppfolgingsBrukerProcessor(
            { NotUnderOppfolging },
            { arenaKontor() },
            { harKaltPåLagreTidligArenaKontor = true }
        )
        processor.handleResult(SkalKanskjeUnderOppfolging(KontorId("dummy"), OffsetDateTime.now(), fnr))
        withClue("Skal lagre i tidlig-arena-kontor hvis bruker kanksje skal komme under oppfølging") {
            harKaltPåLagreTidligArenaKontor shouldBe true
        }
    }

    @Test
    fun `Skal aldri lagre tidligArenaKontor hvis prosesseringsresultat ikke er SkalKanskjeUnderOppfolging`() {
        var harKaltPåLagreTidligArenaKontor = false
        val fnr = randomFnr()
        val processor = EndringPaOppfolgingsBrukerProcessor(
            { NotUnderOppfolging },
            { arenaKontor() },
            { harKaltPåLagreTidligArenaKontor = true }
        )
        processor.handleResult(BeforeCutoff())
        processor.handleResult(HaddeNyereEndring())
        processor.handleResult(MeldingManglerEnhet())
        processor.handleResult(
            SkalLagre(
                oppfolgingsenhet = "en oppfolgingsenhet",
                fnr = fnr,
                endretTidspunkt = OffsetDateTime.now(),
                oppfolgingsperiodeId = OppfolgingsperiodeId(UUID.randomUUID()),
                erFørsteArenaKontorIOppfolgingsperiode = true
            )
        )
        processor.handleResult(IngenEndring())
        processor.handleResult(Feil(Retry("random reason")))

        withClue("Skal kun lagre i tidlig-arena-kontor hvis bruker kanksje skal komme under oppfølging") {
            harKaltPåLagreTidligArenaKontor shouldBe false
        }
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
