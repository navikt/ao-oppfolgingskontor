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
import no.nav.db.entity.OppfolgingsperiodeEntity
import no.nav.db.table.OppfolgingsperiodeTable
import no.nav.domain.OppfolgingsperiodeId
import no.nav.domain.externalEvents.OppfolgingsperiodeStartet
import no.nav.http.client.IdentFunnet
import no.nav.http.client.IdentIkkeFunnet
import no.nav.http.client.IdentOppslagFeil
import no.nav.kafka.processor.Commit
import no.nav.kafka.processor.Forward
import no.nav.kafka.processor.Retry
import no.nav.kafka.processor.Skip
import no.nav.services.OppfolgingsperiodeService
import no.nav.utils.flywayMigrationInTest
import no.nav.utils.gittBrukerUnderOppfolging
import no.nav.utils.randomFnr
import org.apache.kafka.streams.processor.api.Record
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
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
    fun `skal lagre ny oppfolgingsperiode når oppfolgingsperiode-startet (sluttDato er null)`() =
        testApplication {
            val bruker = testBruker()
            application {
                flywayMigrationInTest()
                val consumer = SisteOppfolgingsperiodeProcessor(
                    OppfolgingsperiodeService
                ) { IdentFunnet(bruker.fnr) }

                val record = oppfolgingsperiodeMessage(bruker, sluttDato = null)
                consumer.process(record)

                bruker.skalVæreUnderOppfølging()
            }
        }

    @Test
    fun `skal slette periode når avslutningsmelding (sluttDato != null) kommer `() = testApplication {
        val bruker = testBruker()
        application {
            flywayMigrationInTest()
            val consumer = SisteOppfolgingsperiodeProcessor(
                OppfolgingsperiodeService
            ) { IdentFunnet(bruker.fnr) }

            val startRecord = oppfolgingsperiodeMessage(bruker, sluttDato = null)
            consumer.process(startRecord)
            val sluttRecord = oppfolgingsperiodeMessage(bruker, sluttDato = ZonedDateTime.now())
            consumer.process(sluttRecord)

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
                    OppfolgingsperiodeService
                ) { IdentFunnet(bruker.fnr) }


                val record = oppfolgingsperiodeMessage(bruker, sluttDato = periodeSlutt)

                consumer.process(record)

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
                    OppfolgingsperiodeService,
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
                    OppfolgingsperiodeService,
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
                    OppfolgingsperiodeService,
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
                    OppfolgingsperiodeService,
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
    fun `Skal gi Forward selv når oppfølgingsperiode allerede er registrert`() = testApplication {
        val bruker = testBruker()
        application {
            flywayMigrationInTest()
            val consumer = SisteOppfolgingsperiodeProcessor(OppfolgingsperiodeService) { IdentFunnet(bruker.fnr) }
            val record = oppfolgingsperiodeMessage(bruker, sluttDato = null)
            consumer.process(record)

            val resultMeldingForAndreGang = consumer.process(record)

            resultMeldingForAndreGang.shouldBeInstanceOf<Forward<*, *>>()
            transaction {
                val antallOppfølgingsperioder = OppfolgingsperiodeTable.selectAll()
                    .where { (OppfolgingsperiodeTable.id eq bruker.fnr.value) }
                    .count()
                antallOppfølgingsperioder shouldBe 1
            }
        }
    }

    @Test
    fun `skal gi Retry ved feil på henting av fnr`() =
        testApplication {
            val bruker = testBruker()
            val consumer = SisteOppfolgingsperiodeProcessor(
                OppfolgingsperiodeService,
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
            OppfolgingsperiodeService,
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
            OppfolgingsperiodeService,
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
                OppfolgingsperiodeService,
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
            OppfolgingsperiodeService,
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
            OppfolgingsperiodeService,
        ) { IdentFunnet(bruker.fnr) }

        val result = consumer.process(Record(bruker.aktorId, """{ "lol": "lal" }""", Instant.now().toEpochMilli()))

        result.shouldBeInstanceOf<Retry<*, *>>()
        result.reason shouldBe """
            Klarte ikke behandle oppfolgingsperiode melding: Encountered an unknown key 'lol' at offset 3 at path: ${'$'}
            Use 'ignoreUnknownKeys = true' in 'Json {}' builder or '@JsonIgnoreUnknownKeys' annotation to ignore unknown keys.
            JSON input: { "lol": "lal" }
        """.trimIndent()
    }

    private fun oppfolgingsperiodeMessage(
        bruker: Bruker,
        sluttDato: ZonedDateTime?,
    ): Record<String, String> {
        return Record(bruker.aktorId, """{
            "uuid": "${bruker.oppfolgingsperiodeId.value}",
            "startDato": "${bruker.periodeStart}",
            "sluttDato": ${sluttDato?.let { "\"$it\"" } ?: "null"},
            "aktorId": "${bruker.aktorId}"
        }""", System.currentTimeMillis())
    }
}
