package kafka.consumers

import db.entity.TidligArenaKontorEntity
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk
import no.nav.db.Ident
import no.nav.db.entity.ArenaKontorEntity
import java.time.ZonedDateTime
import java.util.UUID
import no.nav.db.entity.OppfolgingsperiodeEntity
import no.nav.domain.KontorId
import no.nav.domain.KontorTilordning
import no.nav.domain.OppfolgingsperiodeId
import no.nav.domain.events.OppfolgingsPeriodeStartetLokalKontorTilordning
import no.nav.domain.externalEvents.OppfolgingsperiodeStartet
import no.nav.kafka.consumers.EndringPaOppfolgingsBrukerProcessor
import no.nav.kafka.processor.Commit
import no.nav.kafka.processor.Forward
import no.nav.kafka.processor.Retry
import no.nav.kafka.processor.Skip
import no.nav.services.KontorTilordningService
import no.nav.services.NotUnderOppfolging
import no.nav.utils.flywayMigrationInTest
import no.nav.utils.randomFnr
import org.apache.kafka.streams.processor.api.Record
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import services.OppfolgingsperiodeService
import services.ingenSensitivitet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

class OppfolgingshendelseProcessorTest {

    fun Bruker.skalVæreUnderOppfølging(periodeId: OppfolgingsperiodeId? = null) {
        transaction {
            val entity = OppfolgingsperiodeEntity.findById(this@skalVæreUnderOppfølging.fnr.value)
            entity.shouldNotBeNull()
            entity.oppfolgingsperiodeId shouldBe (periodeId ?: this@skalVæreUnderOppfølging.oppfolgingsperiodeId.value)
        }
    }

    fun Bruker.skalIkkeVæreUnderOppfølging() {
        transaction {
            val entity = OppfolgingsperiodeEntity.findById(this@skalIkkeVæreUnderOppfølging.fnr.value)
            entity.shouldBeNull()
        }
    }

    fun testBruker() = Bruker(
        fnr = randomFnr(),
        aktorId = "1234567890123",
        periodeStart = ZonedDateTime.now(ZoneId.of("Europe/Oslo")).minusDays(2),
        oppfolgingsperiodeId = OppfolgingsperiodeId(UUID.randomUUID()),
    )

    @Test
    fun `skal lagre ny oppfolgingsperiode når oppfolgingsperiode-startet`() = testApplication {
        val bruker = testBruker()
        application {
            flywayMigrationInTest()
            val oppfolgingshendelseProcessor = OppfolgingsHendelseProcessor(OppfolgingsperiodeService())
            val record = oppfolgingStartetMelding(bruker)

            val result = oppfolgingshendelseProcessor.process(record)

            result.shouldBeInstanceOf<Forward<Ident, OppfolgingsperiodeStartet>>()
            bruker.skalVæreUnderOppfølging()
        }
    }

    @Test
    fun `skal slette periode når avslutningsmelding kommer `() = testApplication {
        val bruker = testBruker()
        application {
            flywayMigrationInTest()
            val oppfolgingshendelseProcessor = OppfolgingsHendelseProcessor(OppfolgingsperiodeService())
            val hendelseStartResult = oppfolgingshendelseProcessor.process(oppfolgingStartetMelding(bruker))
            hendelseStartResult.shouldBeInstanceOf<Forward<*, *>>()

            val sluttDato = ZonedDateTime.now()
            val hendelseResult = oppfolgingshendelseProcessor.process(oppfolgingAvsluttetMelding(bruker, sluttDato))

            hendelseResult.shouldBeInstanceOf<Commit<*, *>>()
            bruker.skalIkkeVæreUnderOppfølging()
        }
    }

    @Test
    fun `skal ikke lagre oppfolgingsperiode når sluttDato ikke er null (oppfolgingsperiode-avsluttet)`() =
        testApplication {
            val bruker = testBruker()
            val periodeSlutt = ZonedDateTime.now().minusDays(1)

            application {
                flywayMigrationInTest()
                val consumer = OppfolgingsHendelseProcessor(OppfolgingsperiodeService())

                consumer.process(oppfolgingAvsluttetMelding(bruker, sluttDato = periodeSlutt))

                bruker.skalIkkeVæreUnderOppfølging()
            }
        }

    @Test
    fun `skal slette eksisterende oppfolgingsperiode når perioden er avsluttet`() = testApplication {
        val bruker = testBruker()
        val periodeSlutt = ZonedDateTime.now().minusDays(1)

        application {
            flywayMigrationInTest()
            val consumer = OppfolgingsHendelseProcessor(OppfolgingsperiodeService())

            val startPeriodeRecord = oppfolgingStartetMelding(bruker)
            val avsluttetNyerePeriodeRecord = oppfolgingAvsluttetMelding(
                bruker.copy(periodeStart = bruker.periodeStart.plusSeconds(1)), sluttDato = periodeSlutt
            )

            consumer.process(startPeriodeRecord)
            val result = consumer.process(avsluttetNyerePeriodeRecord)

            result.shouldBeInstanceOf<Commit<*, *>>()
            bruker.skalIkkeVæreUnderOppfølging()
        }
    }

    @Test
    fun `skal hoppe over melding hvis den er på en gammel periode`() = testApplication {
        val bruker = testBruker()

        application {
            flywayMigrationInTest()
            val consumer = OppfolgingsHendelseProcessor(OppfolgingsperiodeService())
            val startPeriodeRecord = oppfolgingStartetMelding(bruker)
            val startGammelPeriodeRecord = oppfolgingStartetMelding(
                bruker.copy(
                    oppfolgingsperiodeId = OppfolgingsperiodeId(UUID.randomUUID()),
                    periodeStart = bruker.periodeStart.minusSeconds(1),
                )
            )

            consumer.process(startPeriodeRecord)
            val processingResult = consumer.process(startGammelPeriodeRecord)

            processingResult.shouldBeInstanceOf<Skip<*, *>>()
            bruker.skalVæreUnderOppfølging()
        }
    }

    @Test
    fun `start på nyere periode skal slette gammel periode og lagre ny på gitt ident`() = testApplication {
        val bruker = testBruker()
        val nyerePeriodeId = UUID.randomUUID()
        val nyereStartDato = bruker.periodeStart.plusSeconds(1)

        application {
            flywayMigrationInTest()
            val consumer = OppfolgingsHendelseProcessor(OppfolgingsperiodeService())
            val startPeriodeRecord = oppfolgingStartetMelding(bruker)
            val startNyerePeriodeRecord = oppfolgingStartetMelding(
                bruker.copy(
                    oppfolgingsperiodeId = OppfolgingsperiodeId(nyerePeriodeId),
                    periodeStart = nyereStartDato,
                )
            )

            consumer.process(startPeriodeRecord)
            val processingResult = consumer.process(startNyerePeriodeRecord)

            processingResult.shouldBeInstanceOf<Forward<*, *>>()
            processingResult.forwardedRecord.key() shouldBe bruker.fnr
            processingResult.forwardedRecord.value() shouldBe OppfolgingsperiodeStartet(
                bruker.fnr,
                nyereStartDato,
                OppfolgingsperiodeId(nyerePeriodeId),
                KontorId("4141"),
                null
            )
            processingResult.topic shouldBe null
            transaction {
                val oppfolgingForBruker = OppfolgingsperiodeEntity.findById(bruker.fnr.value)
                oppfolgingForBruker.shouldNotBeNull()
                oppfolgingForBruker.oppfolgingsperiodeId shouldBe nyerePeriodeId
                withClue("startDato lest fra db: ${oppfolgingForBruker.startDato.toInstant()} skal være lik input startDato: ${nyereStartDato.toInstant()}") {
                    // Truncated always rounds down, therefore we add 500 nanos to make it behave like actual rounding like done when
                    // too high precision is inserted into the db
                    oppfolgingForBruker.startDato.toInstant() shouldBe nyereStartDato.toInstant().plusNanos(500)
                        .truncatedTo(ChronoUnit.MICROS)
                }
            }
        }
    }

    @Test
    fun `nyere slutt skal slette gammel periode`() = testApplication {
        val bruker = testBruker()
        val nyereStartDato = bruker.periodeStart.plusSeconds(1)
        val periodeSlutt = nyereStartDato.plusSeconds(1)

        application {
            flywayMigrationInTest()
            val oppfolgingsHendelseProcessor = OppfolgingsHendelseProcessor(OppfolgingsperiodeService())
            val startPeriodeRecord = oppfolgingStartetMelding(bruker)
            val sluttNyerePeriodeRecord = oppfolgingAvsluttetMelding(
                bruker.copy(
                    oppfolgingsperiodeId = OppfolgingsperiodeId(UUID.randomUUID()),
                    periodeStart = nyereStartDato,
                ), sluttDato = periodeSlutt
            )

            oppfolgingsHendelseProcessor.process(startPeriodeRecord)
            val processingResult = oppfolgingsHendelseProcessor.process(sluttNyerePeriodeRecord)

            processingResult.shouldBeInstanceOf<Commit<*, *>>()
            bruker.skalIkkeVæreUnderOppfølging()
        }
    }

    @Test
    fun `skal håndtere deserialiseringsfeil`() {
        val bruker = testBruker()
        val consumer = OppfolgingsHendelseProcessor(OppfolgingsperiodeService())

        val result = consumer.process(Record(bruker.aktorId, """{ "lol": "lal" }""", Instant.now().toEpochMilli()))

        result.shouldBeInstanceOf<Retry<*, *>>()
        result.reason shouldBe """
            Kunne ikke behandle oppfolgingshendelse - <Ukjent hendelsetype>: Class discriminator was missing and no default serializers were registered in the polymorphic scope of 'OppfolgingsHendelseDto'.
            JSON input: {"lol":"lal"}
        """.trimIndent()
    }

    @Test
    fun `skal hoppe over allerede prosesserte meldinger`() {
        val bruker = testBruker()
        flywayMigrationInTest()
        val oppfolgingshendelseProcessor = OppfolgingsHendelseProcessor(OppfolgingsperiodeService())
        val sluttDato = ZonedDateTime.now().plusDays(1)
        val startmelding = oppfolgingStartetMelding(bruker)
        val stoppmelding = oppfolgingAvsluttetMelding(bruker, sluttDato)
        oppfolgingshendelseProcessor.process(startmelding).shouldBeInstanceOf<Forward<*, *>>()
        KontorTilordningService.tilordneKontor(
            OppfolgingsPeriodeStartetLokalKontorTilordning(
                KontorTilordning(bruker.fnr, KontorId("1199"), bruker.oppfolgingsperiodeId), ingenSensitivitet
            )
        )
        oppfolgingshendelseProcessor.process(stoppmelding).shouldBeInstanceOf<Commit<*, *>>()

        val resultStartMeldingPåNytt = oppfolgingshendelseProcessor.process(startmelding)
        val resultStoppMeldingPåNytt = oppfolgingshendelseProcessor.process(stoppmelding)

        resultStartMeldingPåNytt.shouldBeInstanceOf<Skip<*, *>>()
        resultStoppMeldingPåNytt.shouldBeInstanceOf<Skip<*, *>>()
    }

    @Test
    fun `skal håndtere oppfølging avsluttet for en periode vi ikke visste om`() {
        val bruker = testBruker()
        flywayMigrationInTest()
        val processor = OppfolgingsHendelseProcessor(OppfolgingsperiodeService())
        val record = TopicUtils.oppfolgingAvsluttetMelding(bruker, ZonedDateTime.now())

        val result = processor.process(record)

        result.shouldBeInstanceOf<Skip<Ident, OppfolgingsperiodeStartet>>()
    }

    @Test
    fun `skal rydde opp i tidlig-arena-kontor hvis de blir brukt`() {
        val bruker = testBruker()
        flywayMigrationInTest()
        val arenaKontorVeilarboppfolging = "4141"
        val arenaKontor = "4142"
        val arenaKontorFraVeilarboppfolging = mockk<ArenaKontorEntity> {
            every { sistEndretDatoArena } returns OffsetDateTime.now().minusSeconds(1)
            every { kontorId } returns arenaKontorVeilarboppfolging
        }
        val endringPaOppfolgingsBrukerProcessor = EndringPaOppfolgingsBrukerProcessor(
            { arenaKontorFraVeilarboppfolging },
            { NotUnderOppfolging })
        val hendelseProcessor = OppfolgingsHendelseProcessor(OppfolgingsperiodeService())
        endringPaOppfolgingsBrukerProcessor.process(
            TopicUtils.endringPaaOppfolgingsBrukerMessage(
                bruker.fnr,
                arenaKontor,
                Instant.now().atZone(ZoneId.of("Europe/Oslo")).toOffsetDateTime(),
                no.nav.kafka.consumers.FormidlingsGruppe.ARBS,
                no.nav.kafka.consumers.Kvalifiseringsgruppe.IKVAL
            )
        )
        transaction {
            TidligArenaKontorEntity.findById(bruker.fnr.value)?.kontorId shouldBe arenaKontor
        }

        hendelseProcessor.process(TopicUtils.oppfolgingStartetMelding(bruker))

        transaction {
            TidligArenaKontorEntity.findById(bruker.fnr.value) shouldBe null
        }
    }

    fun oppfolgingStartetMelding(bruker: Bruker): Record<String, String> = TopicUtils.oppfolgingStartetMelding(bruker)

    fun oppfolgingAvsluttetMelding(bruker: Bruker, sluttDato: ZonedDateTime): Record<String, String> =
        TopicUtils.oppfolgingAvsluttetMelding(bruker, sluttDato)
}
