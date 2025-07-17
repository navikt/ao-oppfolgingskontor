package kafka.consumers

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.server.testing.testApplication
import java.time.ZonedDateTime
import java.util.UUID
import no.nav.db.Fnr
import no.nav.db.entity.OppfolgingsperiodeEntity
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
import no.nav.services.AktivOppfolgingsperiode
import no.nav.services.AutomatiskKontorRutingService
import no.nav.services.KontorForGtNrFantKontor
import no.nav.services.KontorTilordningService
import no.nav.services.OppfolgingsperiodeService
import no.nav.utils.flywayMigrationInTest
import no.nav.utils.randomFnr
import org.apache.kafka.streams.processor.api.Record
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test

class OppfolgingsPeriodeConsumerTest {

    data class Bruker(
        val fnr: Fnr,
        val aktorId: String,
        val oppfolgingsperiodeId: OppfolgingsperiodeId,
        val periodeStart: ZonedDateTime
    )

    fun testBruker() = Bruker(
        fnr = randomFnr(),
        aktorId = "1234567890123",
        periodeStart = ZonedDateTime.now().minusDays(2),
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
                    OppfolgingsperiodeService,
                    { FnrFunnet(bruker.fnr) }
                )

                val record = oppfolgingsperiodeMessage(
                    bruker,
                    sluttDato = null,
                )

                consumer.consume(record)

                transaction {
                    val entity = OppfolgingsperiodeEntity.findById(bruker.fnr.value)
                    entity.shouldNotBeNull()
                    entity.fnr.value shouldBe bruker.fnr.value
//                    entity.startDato shouldHaveSameInstantAs bruker.periodeStart.toOffsetDateTime()
                    entity.oppfolgingsperiodeId shouldBe bruker.oppfolgingsperiodeId.value
                }
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
                    OppfolgingsperiodeService,
                    { FnrFunnet(bruker.fnr) }
                )

                val record = oppfolgingsperiodeMessage(
                    bruker,
                    sluttDato = periodeSlutt,
                )

                consumer.consume(record)

                transaction {
                    val periodeForBruker = OppfolgingsperiodeEntity.findById(bruker.fnr.value)
                    periodeForBruker.shouldBeNull()
                }
            }
        }

    @Test
    fun `skal slette eksiterende oppfolgingsperiode når perioden er avsluttet`() =
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
                    { FnrFunnet(bruker.fnr) }
                )

                val startPeriodeRecord = oppfolgingsperiodeMessage(bruker, sluttDato = null)
                val avsluttePeriodeRecord = oppfolgingsperiodeMessage(bruker, sluttDato = periodeSlutt)

                consumer.consume(startPeriodeRecord)
                consumer.consume(avsluttePeriodeRecord)

                transaction {
                    val oppfolgingForBruker = OppfolgingsperiodeEntity.findById(bruker.fnr.value)
                    oppfolgingForBruker shouldBe null
                }
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
            { AktivOppfolgingsperiode(fnr, oppfolgingsperiodeId) }
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
            "aktorId": "${bruker.aktorId}",
            "startetBegrunnelse": "ARBEIDSSOKER"
        }""", System.currentTimeMillis())
    }
}
