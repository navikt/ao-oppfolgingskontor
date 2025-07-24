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
import no.nav.domain.HarSkjerming
import no.nav.domain.HarStrengtFortroligAdresse
import no.nav.domain.KontorId
import no.nav.domain.OppfolgingsperiodeId
import no.nav.http.client.AlderFunnet
import no.nav.http.client.FnrFunnet
import no.nav.http.client.HarStrengtFortroligAdresseFunnet
import no.nav.http.client.SkjermingFunnet
import no.nav.http.client.arbeidssogerregisteret.ProfileringFunnet
import no.nav.http.client.arbeidssogerregisteret.ProfileringsResultat
import no.nav.kafka.consumers.OppfolgingsPeriodeConsumer
import no.nav.kafka.processor.Commit
import no.nav.kafka.processor.Skip
import no.nav.services.AktivOppfolgingsperiode
import no.nav.services.AutomatiskKontorRutingService
import no.nav.services.KontorForGtNrFantKontor
import no.nav.services.KontorTilordningService
import no.nav.services.OppfolgingsperiodeService
import no.nav.utils.flywayMigrationInTest
import no.nav.utils.randomFnr
import org.apache.kafka.streams.processor.api.Record
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

class OppfolgingsPeriodeConsumerTest {

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
                val consumer = OppfolgingsPeriodeConsumer(
                    createAutomatiskKontorRutingService(bruker.fnr, bruker.oppfolgingsperiodeId),
                    OppfolgingsperiodeService
                ) { FnrFunnet(bruker.fnr) }


                val record = oppfolgingsperiodeMessage(bruker, sluttDato = null)
                consumer.consume(record)

                bruker.skalVæreUnderOppfølging()
            }
        }

    @Test
    fun `skal ikke lagre oppfolgingsperiode når sluttDato ikke er null (oppfolgingsperiode-avsluttet)`() =
        testApplication {
            val bruker = testBruker()
            val periodeSlutt = ZonedDateTime.now().minusDays(1)

            application {
                flywayMigrationInTest()
                val consumer = OppfolgingsPeriodeConsumer(createAutomatiskKontorRutingService(
                        bruker.fnr,
                    bruker.oppfolgingsperiodeId
                    ),
                    OppfolgingsperiodeService
                ) { FnrFunnet(bruker.fnr) }


                val record = oppfolgingsperiodeMessage(bruker, sluttDato = periodeSlutt)

                consumer.consume(record)

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
                val consumer = OppfolgingsPeriodeConsumer(createAutomatiskKontorRutingService(
                    bruker.fnr,
                    bruker.oppfolgingsperiodeId
                    ),
                    OppfolgingsperiodeService,
                ) { FnrFunnet(bruker.fnr) }


                val startPeriodeRecord = oppfolgingsperiodeMessage(bruker, sluttDato = null)
                val avsluttetNyerePeriodeRecord = oppfolgingsperiodeMessage(
                    bruker.copy(periodeStart = bruker.periodeStart.plusSeconds(1)), sluttDato = periodeSlutt)

                consumer.consume(startPeriodeRecord)
                val result = consumer.consume(avsluttetNyerePeriodeRecord)

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
                val consumer = OppfolgingsPeriodeConsumer(createAutomatiskKontorRutingService(
                    bruker.fnr,
                    bruker.oppfolgingsperiodeId
                ),
                    OppfolgingsperiodeService,
                ) { FnrFunnet(bruker.fnr) }


                val startPeriodeRecord = oppfolgingsperiodeMessage(bruker, sluttDato = null)
                val startGammelPeriodeRecord = oppfolgingsperiodeMessage(
                    bruker.copy(
                        oppfolgingsperiodeId = OppfolgingsperiodeId(UUID.randomUUID()),
                        periodeStart = bruker.periodeStart.minusSeconds(1),
                    ),
                    sluttDato = null)

                consumer.consume(startPeriodeRecord)
                val processingResult = consumer.consume(startGammelPeriodeRecord)

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
                val consumer = OppfolgingsPeriodeConsumer(createAutomatiskKontorRutingService(
                    bruker.fnr,
                    bruker.oppfolgingsperiodeId
                ),
                    OppfolgingsperiodeService,
                ) { FnrFunnet(bruker.fnr) }


                val startPeriodeRecord = oppfolgingsperiodeMessage(bruker, sluttDato = null)
                val startNyerePeriodeRecord = oppfolgingsperiodeMessage(
                    bruker.copy(
                        oppfolgingsperiodeId = OppfolgingsperiodeId(nyerePeriodeId),
                        periodeStart = nyereStartDato,
                    ),
                    sluttDato = null)

                consumer.consume(startPeriodeRecord)
                val processingResult = consumer.consume(startNyerePeriodeRecord)

                processingResult.shouldBeInstanceOf<Commit<*, *>>()
                transaction {
                    val oppfolgingForBruker = OppfolgingsperiodeEntity.findById(bruker.fnr.value)
                    oppfolgingForBruker.shouldNotBeNull()
                    oppfolgingForBruker.oppfolgingsperiodeId shouldBe nyerePeriodeId
                    withClue("startDato lest fra db: ${oppfolgingForBruker.startDato.toInstant()} skal være lik input startDato: ${nyereStartDato.toInstant()}") {
                        // Truncated always rounds down, therefore we add 500 nanos to make it behave like actual rounding like done when
                        // too highe precision is inserted into the db
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
                val consumer = OppfolgingsPeriodeConsumer(createAutomatiskKontorRutingService(
                    bruker.fnr,
                    bruker.oppfolgingsperiodeId
                ),
                    OppfolgingsperiodeService,
                ) { FnrFunnet(bruker.fnr) }


                val startPeriodeRecord = oppfolgingsperiodeMessage(bruker, sluttDato = null)
                val sluttNyerePeriodeRecord = oppfolgingsperiodeMessage(
                    bruker.copy(
                        oppfolgingsperiodeId = OppfolgingsperiodeId(UUID.randomUUID()),
                        periodeStart = nyereStartDato,
                    ),
                    sluttDato = periodeSlutt)

                consumer.consume(startPeriodeRecord)
                val processingResult = consumer.consume(sluttNyerePeriodeRecord)

                processingResult.shouldBeInstanceOf<Commit<*, *>>()
                bruker.skalIkkeVæreUnderOppfølging()
            }
        }

    private fun createAutomatiskKontorRutingService(
        fnr: Fnr,
        oppfolgingsperiodeId: OppfolgingsperiodeId,
    ): AutomatiskKontorRutingService {
        val kontor = KontorId("2228")
        return AutomatiskKontorRutingService(
            KontorTilordningService::tilordneKontor,
            { _, a, b -> KontorForGtNrFantKontor(kontor, b, a) },
            { AlderFunnet(40) },
            { FnrFunnet(fnr) },
            { ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER) },
            { SkjermingFunnet(HarSkjerming(false)) },
            { HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false)) },
            { AktivOppfolgingsperiode(fnr, oppfolgingsperiodeId, OffsetDateTime.now()) }
        )
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
