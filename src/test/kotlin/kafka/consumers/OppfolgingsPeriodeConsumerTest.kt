package kafka.consumers

import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.server.testing.testApplication
import java.time.ZonedDateTime
import java.util.UUID
import no.nav.db.Fnr
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

class SisteOppfolgingsperiodeProcessorTest {

    data class Bruker(
        val fnr: Fnr,
        val aktorId: String,
        val oppfolgingsperiodeId: OppfolgingsperiodeId,
        val periodeStart: ZonedDateTime
    ) {
        fun skalVæreUnderOppfølging(periodeId: OppfolgingsperiodeId? = null) {
            transaction {
                val entity = OppfolgingsperiodeEntity.findById(this@Bruker.fnr.value)
                entity.shouldNotBeNull()
                entity.oppfolgingsperiodeId shouldBe (periodeId ?: this@Bruker.oppfolgingsperiodeId.value)
            }
        }
        fun skalIkkeVæreUnderOppfølging() {
            transaction {
                val entity = OppfolgingsperiodeEntity.findById(this@Bruker.fnr.value)
                entity.shouldBeNull()
            }
        }
        fun skalHaArenaKontor(kontor: KontorId) {
            transaction {
                ArenaKontorEntity[this@Bruker.fnr.value].kontorId shouldBe kontor.id
            }
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

    fun defaultConsumerSetup(bruker: Bruker): Pair<OppfolgingsHendelseProcessor, SisteOppfolgingsperiodeProcessor> {
        return OppfolgingsHendelseProcessor(OppfolgingsperiodeService()) to SisteOppfolgingsperiodeProcessor(
            OppfolgingsperiodeService()
        ) { IdentFunnet(bruker.fnr) }
    }

    @Test
    fun `skal lagre ny oppfolgingsperiode når oppfolgingsperiode-startet (sluttDato er null)`() =
        testApplication {
            val bruker = testBruker()
            application {
                flywayMigrationInTest()
                val (oppfolgingshendelseProcessor, sisteOppfolgingsperiodeProcessor) = defaultConsumerSetup(bruker)

                val oppfolgingshendelseRecord = oppfolgingStartetMelding(bruker)
                val oppfolgingsperiodeRecord = oppfolgingsperiodeMessage(bruker)

                val resultSisteOppfolgingsperiode = sisteOppfolgingsperiodeProcessor.process(oppfolgingsperiodeRecord)
                val resultOppfolgingshendelse = oppfolgingshendelseProcessor.process(oppfolgingshendelseRecord)

                resultSisteOppfolgingsperiode.shouldBeInstanceOf<Forward<*, *>>()
                resultOppfolgingshendelse.shouldBeInstanceOf<Commit<*, *>>()
                bruker.skalVæreUnderOppfølging()
                bruker.skalHaArenaKontor(defaultArenaKontor)
            }
        }

    @Test
    fun `skal slette periode når avslutningsmelding (sluttDato != null) kommer `() = testApplication {
        val bruker = testBruker()
        application {
            flywayMigrationInTest()
            val (oppfolgingshendelseProcessor, sisteOppfolgingsperiodeProcessor) = defaultConsumerSetup(bruker)

            val sisteResult = sisteOppfolgingsperiodeProcessor.process(oppfolgingsperiodeMessage(bruker))
            val hendelseStartResult = oppfolgingshendelseProcessor.process(oppfolgingStartetMelding(bruker))
            sisteResult.shouldBeInstanceOf<Forward<*, *>>()
            hendelseStartResult.shouldBeInstanceOf<Commit<*, *>>()

            val sluttDato = ZonedDateTime.now()
            val sistOppResult = sisteOppfolgingsperiodeProcessor.process(oppfolgingsperiodeMessage(bruker, sluttDato))
            val hendelseResult =  oppfolgingshendelseProcessor.process(oppfolgingAvsluttetMelding(bruker, sluttDato))

            sistOppResult.shouldBeInstanceOf<Commit<*, *>>()
            hendelseResult.shouldBeInstanceOf<Skip<*, *>>()
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
                val consumer = SisteOppfolgingsperiodeProcessor(
                    OppfolgingsperiodeService()
                ) { IdentFunnet(bruker.fnr) }

                consumer.process(oppfolgingsperiodeMessage(bruker, sluttDato = periodeSlutt))

                bruker.skalIkkeVæreUnderOppfølging()
            }
        }

    @Test
    fun `skal slette eksisterende oppfolgingsperiode når perioden er avsluttet`() =
        testApplication {
            val bruker = testBruker()
            val periodeSlutt = ZonedDateTime.now().minusDays(1)

            application {
                flywayMigrationInTest()
                val consumer = SisteOppfolgingsperiodeProcessor(
                    OppfolgingsperiodeService(),
                ) { IdentFunnet(bruker.fnr) }

                val startPeriodeRecord = oppfolgingsperiodeMessage(bruker, sluttDato = null)
                val avsluttetNyerePeriodeRecord = oppfolgingsperiodeMessage(
                    bruker.copy(periodeStart = bruker.periodeStart.plusSeconds(1)), sluttDato = periodeSlutt)

                consumer.process(startPeriodeRecord)
                val result = consumer.process(avsluttetNyerePeriodeRecord)

                result.shouldBeInstanceOf<Commit<*, *>>()
                bruker.skalIkkeVæreUnderOppfølging()
            }
        }

    @Test
    fun `skal hoppe over melding hvis den er på en gammel periode`() =
        testApplication {
            val bruker = testBruker()

            application {
                flywayMigrationInTest()
                val consumer = SisteOppfolgingsperiodeProcessor(
                    OppfolgingsperiodeService(),
                ) { IdentFunnet(bruker.fnr) }
                val startPeriodeRecord = oppfolgingsperiodeMessage(bruker, sluttDato = null)
                val startGammelPeriodeRecord = oppfolgingsperiodeMessage(
                    bruker.copy(
                        oppfolgingsperiodeId = OppfolgingsperiodeId(UUID.randomUUID()),
                        periodeStart = bruker.periodeStart.minusSeconds(1),
                    ),
                    sluttDato = null)

                consumer.process(startPeriodeRecord)
                val processingResult = consumer.process(startGammelPeriodeRecord)

                processingResult.shouldBeInstanceOf<Skip<*, *>>()
                bruker.skalVæreUnderOppfølging()
            }
        }

    @Test
    fun `start på nyere periode skal slette gammel periode og lagre ny på gitt ident`() =
        testApplication {
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
                    ),
                    sluttDato = null)

                consumer.process(startPeriodeRecord)
                val processingResult = consumer.process(startNyerePeriodeRecord)

                processingResult.shouldBeInstanceOf<Forward<*,*>>()
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
                        oppfolgingForBruker.startDato.toInstant() shouldBe nyereStartDato
                            .toInstant().plusNanos(500).truncatedTo(ChronoUnit.MICROS)
                    }
                }
            }
        }

    @Test
    fun `nyere slutt skal slette gammel periode`() =
        testApplication {
            val bruker = testBruker()
            val nyereStartDato = bruker.periodeStart.plusSeconds(1)
            val periodeSlutt = nyereStartDato.plusSeconds(1)

            application {
                flywayMigrationInTest()
                val consumer = SisteOppfolgingsperiodeProcessor(
                    OppfolgingsperiodeService(),
                ) { IdentFunnet(bruker.fnr) }
                val startPeriodeRecord = oppfolgingsperiodeMessage(bruker, sluttDato = null)
                val sluttNyerePeriodeRecord = oppfolgingsperiodeMessage(
                    bruker.copy(
                        oppfolgingsperiodeId = OppfolgingsperiodeId(UUID.randomUUID()),
                        periodeStart = nyereStartDato,
                    ),
                    sluttDato = periodeSlutt)

                consumer.process(startPeriodeRecord)
                val processingResult = consumer.process(sluttNyerePeriodeRecord)

                processingResult.shouldBeInstanceOf<Commit<*, *>>()
                bruker.skalIkkeVæreUnderOppfølging()
            }
        }

    @Test
    fun `skal gi Retry ved feil på henting av fnr`() =
        testApplication {
            val bruker = testBruker()
            val consumer = SisteOppfolgingsperiodeProcessor(
                OppfolgingsperiodeService(),
            ) { IdentOppslagFeil("Feil ved henting av fnr") }

            val result = consumer.process(oppfolgingsperiodeMessage(
                bruker,
                null
            ))

            result.shouldBeInstanceOf<Retry<*, *>>()
            result.reason shouldBe "Kunne ikke behandle oppfolgingsperiode melding: Feil ved henting av fnr"
        }

    @Test
    fun `skal gi Skip ved feil på henting av fnr men konfigurert til å hoppe over melding`() {
        val bruker = testBruker()
        val consumer = SisteOppfolgingsperiodeProcessor(
            OppfolgingsperiodeService(),
            true
        ) { IdentOppslagFeil("Fant ikke person: not_found") }

        val result = consumer.process(oppfolgingsperiodeMessage(
            bruker,
            null
        ))

        result.shouldBeInstanceOf<Skip<*, *>>()
    }

    @Test
    fun `skal gi Retry ved feil på henting av fnr som ikke er not_found fra pdl når konfigurert til å hoppe de typene melding`() {
        val bruker = testBruker()
        val consumer = SisteOppfolgingsperiodeProcessor(
            OppfolgingsperiodeService(),
            true
        ) { IdentOppslagFeil("Fant ikke person: not_not_found") }

        val result = consumer.process(oppfolgingsperiodeMessage(
            bruker,
            null
        ))

        result.shouldBeInstanceOf<Retry<*, *>>()
    }

    @Test
    fun `skal gi Commit ved feil på henting av fnr som ER not_found fra pdl og perioden er avsluttet`() = testApplication {
        application {
            flywayMigrationInTest()

            val bruker = testBruker()
            gittBrukerUnderOppfolging(bruker)
            val consumer = SisteOppfolgingsperiodeProcessor(
                OppfolgingsperiodeService(),
                false
            ) { IdentOppslagFeil("Fant ikke person: not_found") }

            val result = consumer.process(oppfolgingsperiodeMessage(
                bruker,
                ZonedDateTime.now()
            ))

            result.shouldBeInstanceOf<Commit<*, *>>()
            bruker.skalIkkeVæreUnderOppfølging()
        }
    }

    @Test
    fun `skal gi Retry når ingen fnr finnes`() {
        val bruker = testBruker()
        val consumer = SisteOppfolgingsperiodeProcessor(
            OppfolgingsperiodeService(),
        ) { IdentIkkeFunnet("Finnes ingen fnr") }

        val result = consumer.process(oppfolgingsperiodeMessage(
            bruker,
            null
        ))

        result.shouldBeInstanceOf<Retry<*, *>>()
        result.reason shouldBe "Kunne ikke behandle oppfolgingsperiode melding: Finnes ingen fnr"
    }

    @Test
    fun `skal håndtere deserialiseringsfeil`() {
        val bruker = testBruker()
        val consumer = SisteOppfolgingsperiodeProcessor(
            OppfolgingsperiodeService(),
        ) { IdentFunnet(bruker.fnr) }

        val result = consumer.process(Record(bruker.aktorId, """{ "lol": "lal" }""", Instant.now().toEpochMilli()))

        result.shouldBeInstanceOf<Retry<*, *>>()
        result.reason shouldBe """
            Klarte ikke behandle oppfolgingsperiode melding: Encountered an unknown key 'lol' at offset 3 at path: ${'$'}
            Use 'ignoreUnknownKeys = true' in 'Json {}' builder or '@JsonIgnoreUnknownKeys' annotation to ignore unknown keys.
            JSON input: { "lol": "lal" }
        """.trimIndent()
    }

    @Test
    fun `skal håndtere gamle meldinger på ny topic`() {
        val bruker = testBruker()
        flywayMigrationInTest()
        val (hendelserProcessor, sistePeriodeProcessor) = defaultConsumerSetup(bruker)
        val sluttDato = ZonedDateTime.now().plusDays(1)
        val opprinneligStartMelding = oppfolgingsperiodeMessage(bruker)
        val opprinneligStoppMelding = oppfolgingsperiodeMessage(bruker, sluttDato)
        sistePeriodeProcessor.process(opprinneligStartMelding).shouldBeInstanceOf<Forward<*, *>>()
        KontorTilordningService.tilordneKontor(OppfolgingsPeriodeStartetLokalKontorTilordning(
            KontorTilordning(bruker.fnr, KontorId("1199"), bruker.oppfolgingsperiodeId),
            ingenSensitivitet
        ))
        sistePeriodeProcessor.process(opprinneligStoppMelding).shouldBeInstanceOf<Commit<*, *>>()

        val startMeldingPåNyttTopic = oppfolgingStartetMelding(bruker)
        val sluttMeldingPåNyttTopic = oppfolgingAvsluttetMelding(bruker, sluttDato)
        val resultStartMeldingPåNyttTopic = hendelserProcessor.process(startMeldingPåNyttTopic)
        val resultStoppMeldingPåNyttTopic = hendelserProcessor.process(sluttMeldingPåNyttTopic)

        resultStartMeldingPåNyttTopic.shouldBeInstanceOf<Skip<*, *>>()
        resultStoppMeldingPåNyttTopic.shouldBeInstanceOf<Skip<*, *>>()
    }

    private fun oppfolgingsperiodeMessage(
        bruker: Bruker,
        sluttDato: ZonedDateTime? = null,
    ) = TopicUtils.oppfolgingsperiodeMessage(bruker, sluttDato)

    val defaultArenaKontor = KontorId("4141")
    fun oppfolgingStartetMelding(bruker: Bruker): Record<String, String>
        = TopicUtils.oppfolgingStartetMelding(bruker)

    fun oppfolgingAvsluttetMelding(bruker: Bruker, sluttDato: ZonedDateTime): Record<String, String>
        = TopicUtils.oppfolgingAvsluttetMelding(bruker, sluttDato)
}
