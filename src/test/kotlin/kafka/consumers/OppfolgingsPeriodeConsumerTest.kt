package kafka.consumers

import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.server.testing.testApplication
import java.time.ZonedDateTime
import java.util.UUID
import no.nav.db.entity.ArenaKontorEntity
import no.nav.db.entity.OppfolgingsperiodeEntity
import no.nav.domain.KontorId
import no.nav.domain.KontorTilordning
import no.nav.domain.OppfolgingsperiodeId
import no.nav.domain.events.OppfolgingsPeriodeStartetLokalKontorTilordning
import no.nav.domain.externalEvents.OppfolgingsperiodeStartet
import no.nav.http.client.IdentFunnet
import no.nav.http.client.IdentIkkeFunnet
import no.nav.http.client.IdentOppslagFeil
import no.nav.kafka.processor.Commit
import no.nav.kafka.processor.Forward
import no.nav.kafka.processor.Retry
import no.nav.kafka.processor.Skip
import no.nav.services.KontorTilordningService
import no.nav.utils.flywayMigrationInTest
import no.nav.utils.gittBrukerUnderOppfolging
import no.nav.utils.randomFnr
import org.apache.kafka.streams.processor.api.Record
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import services.OppfolgingsperiodeService
import services.ingenSensitivitet
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

class OppfolgingsperiodeProcessorTest {

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

    fun Bruker.skalHaArenaKontor(kontor: KontorId) {
        transaction {
            ArenaKontorEntity[this@skalHaArenaKontor.fnr.value].kontorId shouldBe kontor.id
        }
    }

    fun gittBrukerUnderOppfolging(bruker: Bruker) {
        gittBrukerUnderOppfolging(bruker.fnr, bruker.oppfolgingsperiodeId)
    }

    fun testBruker() = Bruker(
        fnr = randomFnr(),
        aktorId = "1234567890123",
        periodeStart = ZonedDateTime.now(ZoneId.of("Europe/Oslo")).minusDays(2),
        oppfolgingsperiodeId = OppfolgingsperiodeId(UUID.randomUUID()),
    )

    @Test
    fun `skal lagre ny oppfolgingsperiode når oppfolgingsperiode-startet (sluttDato er null)`() = testApplication {
        val bruker = testBruker()
        application {
            flywayMigrationInTest()
            val oppfolgingshendelseProcessor = OppfolgingsHendelseProcessor(OppfolgingsperiodeService())
            val record = oppfolgingStartetMelding(bruker)

            val result = oppfolgingshendelseProcessor.process(record)

            result.shouldBeInstanceOf<Commit<*, *>>()
            bruker.skalVæreUnderOppfølging()
            bruker.skalHaArenaKontor(defaultArenaKontor)
        }
    }

    @Test
    fun `skal slette periode når avslutningsmelding (sluttDato != null) kommer `() = testApplication {
        val bruker = testBruker()
        application {
            flywayMigrationInTest()
            val oppfolgingshendelseProcessor = OppfolgingsHendelseProcessor(OppfolgingsperiodeService())
            val hendelseStartResult = oppfolgingshendelseProcessor.process(oppfolgingStartetMelding(bruker))
            hendelseStartResult.shouldBeInstanceOf<Commit<*, *>>()

            val sluttDato = ZonedDateTime.now()
            val hendelseResult = oppfolgingshendelseProcessor.process(oppfolgingAvsluttetMelding(bruker, sluttDato))

            hendelseResult.shouldBeInstanceOf<Skip<*, *>>()
            bruker.skalIkkeVæreUnderOppfølging()
            bruker.skalHaArenaKontor(defaultArenaKontor)
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

                consumer.process(oppfolgingsperiodeMessage(bruker, sluttDato = periodeSlutt))

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

            val startPeriodeRecord = oppfolgingsperiodeMessage(bruker, sluttDato = null)
            val avsluttetNyerePeriodeRecord = oppfolgingsperiodeMessage(
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
            val startPeriodeRecord = oppfolgingsperiodeMessage(bruker, sluttDato = null)
            val startGammelPeriodeRecord = oppfolgingsperiodeMessage(
                bruker.copy(
                    oppfolgingsperiodeId = OppfolgingsperiodeId(UUID.randomUUID()),
                    periodeStart = bruker.periodeStart.minusSeconds(1),
                ), sluttDato = null
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
            val consumer = SisteOppfolgingsperiodeProcessor(
                OppfolgingsperiodeService(),
            ) { IdentFunnet(bruker.fnr) }
            val startPeriodeRecord = oppfolgingsperiodeMessage(bruker, sluttDato = null)
            val startNyerePeriodeRecord = oppfolgingsperiodeMessage(
                bruker.copy(
                    oppfolgingsperiodeId = OppfolgingsperiodeId(nyerePeriodeId),
                    periodeStart = nyereStartDato,
                ), sluttDato = null
            )

            consumer.process(startPeriodeRecord)
            val processingResult = consumer.process(startNyerePeriodeRecord)

            processingResult.shouldBeInstanceOf<Forward<*, *>>()
            processingResult.forwardedRecord.key() shouldBe bruker.fnr
            processingResult.forwardedRecord.value() shouldBe OppfolgingsperiodeStartet(
                bruker.fnr,
                nyereStartDato,
                OppfolgingsperiodeId(nyerePeriodeId),
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
            val consumer = OppfolgingsHendelseProcessor(OppfolgingsperiodeService())
            val startPeriodeRecord = oppfolgingsperiodeMessage(bruker, sluttDato = null)
            val sluttNyerePeriodeRecord = oppfolgingsperiodeMessage(
                bruker.copy(
                    oppfolgingsperiodeId = OppfolgingsperiodeId(UUID.randomUUID()),
                    periodeStart = nyereStartDato,
                ), sluttDato = periodeSlutt
            )

            consumer.process(startPeriodeRecord)
            val processingResult = consumer.process(sluttNyerePeriodeRecord)

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
            Klarte ikke behandle oppfolgingsperiode melding: Encountered an unknown key 'lol' at offset 3 at path: ${'$'}
            Use 'ignoreUnknownKeys = true' in 'Json {}' builder or '@JsonIgnoreUnknownKeys' annotation to ignore unknown keys.
            JSON input: { "lol": "lal" }
        """.trimIndent()
    }

    @Test
    fun `skal hoppe over allerede prosesserte meldinger`() {
        val bruker = testBruker()
        flywayMigrationInTest()
        val oppfolgingshendelseProcessor = OppfolgingsHendelseProcessor(OppfolgingsperiodeService())
        val sluttDato = ZonedDateTime.now().plusDays(1)
        val startmelding = oppfolgingsperiodeMessage(bruker)
        val stoppmelding = oppfolgingsperiodeMessage(bruker, sluttDato)
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

    private fun oppfolgingsperiodeMessage(
        bruker: Bruker,
        sluttDato: ZonedDateTime? = null,
    ) = TopicUtils.oppfolgingsperiodeMessage(bruker, sluttDato)

    val defaultArenaKontor = KontorId("4141")
    fun oppfolgingStartetMelding(bruker: Bruker): Record<String, String> = TopicUtils.oppfolgingStartetMelding(bruker)

    fun oppfolgingAvsluttetMelding(bruker: Bruker, sluttDato: ZonedDateTime): Record<String, String> =
        TopicUtils.oppfolgingAvsluttetMelding(bruker, sluttDato)
}
