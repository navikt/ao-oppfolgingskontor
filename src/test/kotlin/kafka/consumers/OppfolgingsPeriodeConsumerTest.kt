package kafka.consumers

import io.kotest.matchers.date.shouldHaveSameInstantAs
import io.kotest.matchers.shouldBe
import io.ktor.server.testing.testApplication
import java.time.ZonedDateTime
import java.util.UUID
import no.nav.db.Fnr
import no.nav.db.entity.OppfolgingsperiodeEntity
import no.nav.domain.HarSkjerming
import no.nav.domain.HarStrengtFortroligAdresse
import no.nav.domain.KontorId
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
import org.apache.kafka.streams.processor.api.Record
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test

class OppfolgingsPeriodeConsumerTest {

    data class Bruker(
        val fnr: Fnr,
        val aktorId: String,
        val oppfolgingsperiodeId: UUID,
        val periodeStart: ZonedDateTime
    )

    val bruker = Bruker(
        fnr = Fnr("12345678901"),
        aktorId = "1234567890123",
        periodeStart = ZonedDateTime.now().minusDays(2),
        oppfolgingsperiodeId = UUID.randomUUID(),
    )

    @Test
    fun `skal lagre ny oppfolgingsperiode når oppfolgingsperiode-startet (sluttDato er null)`() =
        testApplication {

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

                consumer.consume(record, null)

                transaction {
                    val entityCount = OppfolgingsperiodeEntity.count()
                    entityCount shouldBe 1

                    // Get the actual entity
                    val entity = OppfolgingsperiodeEntity.all().first()
                    entity.fnr.value shouldBe bruker.fnr.value
                    entity.startDato shouldHaveSameInstantAs bruker.periodeStart.toOffsetDateTime()
                    entity.oppfolgingsperiodeId shouldBe bruker.oppfolgingsperiodeId
                }
            }
        }

    @Test
    fun `skal ikke lagre oppfolgingsperiode når sluttDato ikke er null (oppfolgingsperiode-avsluttet)`() =
        testApplication {
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

                val result = consumer.consume(record, null)

                transaction {
                    val entityCount = OppfolgingsperiodeEntity.count()
                    entityCount shouldBe 0
                }
            }
        }

    @Test
    fun `skal slette eksiterende oppfolgingsperiode når perioden er avsluttet`() =
        testApplication {
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
                val avsluttePeriodeRecord = oppfolgingsperiodeMessage(bruker, sluttDato = periodeSlutt,)

                consumer.consume(startPeriodeRecord, null)
                consumer.consume(avsluttePeriodeRecord, null)

                transaction {
                    val entityCount = OppfolgingsperiodeEntity.count()
                    entityCount shouldBe 0
                }
            }
        }

    private fun createAutomatiskKontorRutingService(
        fnr: Fnr,
        oppfolgingsperiodeId: UUID
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
            "uuid": "${bruker.oppfolgingsperiodeId}",
            "startDato": "${bruker.periodeStart}",
            "sluttDato": ${sluttDato?.let { "\"$it\"" } ?: "null"},
            "aktorId": "${bruker.aktorId}",
            "startetBegrunnelse": "ARBEIDSSOKER"
        }""", System.currentTimeMillis())
    }
}
