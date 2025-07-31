package kafka.consumers

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kafka.retry.TestLockProvider
import kafka.retry.library.internal.setupKafkaMock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import no.nav.db.Fnr
import no.nav.db.entity.ArbeidsOppfolgingKontorEntity
import no.nav.db.table.ArbeidsOppfolgingKontorTable
import no.nav.domain.HarSkjerming
import no.nav.domain.HarStrengtFortroligAdresse
import no.nav.domain.KontorId
import no.nav.domain.OppfolgingsperiodeId
import no.nav.http.client.AlderFunnet
import no.nav.http.client.FnrFunnet
import no.nav.http.client.GeografiskTilknytningBydelNr
import no.nav.http.client.HarStrengtFortroligAdresseFunnet
import no.nav.http.client.SkjermingFunnet
import no.nav.http.client.arbeidssogerregisteret.ProfileringFunnet
import no.nav.http.client.arbeidssogerregisteret.ProfileringsResultat
import no.nav.kafka.config.configureTopology
import no.nav.kafka.consumers.EndringPaOppfolgingsBrukerProcessor
import no.nav.kafka.consumers.KontortilordningsProcessor
import no.nav.kafka.consumers.LeesahProcessor
import no.nav.kafka.consumers.SkjermingProcessor
import no.nav.security.mock.oauth2.http.put
import no.nav.services.AktivOppfolgingsperiode
import no.nav.services.AutomatiskKontorRutingService
import no.nav.services.KontorForGtNrFantDefaultKontor
import no.nav.services.KontorTilordningService
import no.nav.services.OppfolgingsperiodeService
import no.nav.utils.flywayMigrationInTest
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.util.UUID

class BigAppTest {

    @Test
    fun `app should work`() = testApplication {
        val fnr = Fnr("22325678901")
        val aktorId = "22325678902"
        val kontor = KontorId("2232")
        val oppfolgingsperiodeId = OppfolgingsperiodeId(UUID.randomUUID())
        environment {
            config = ApplicationConfig("application.yaml")
        }
        application {
            flywayMigrationInTest()
            val sistePeriodeProcessor = SisteOppfolgingsperiodeProcessor(
                OppfolgingsperiodeService,
                false
            ) { FnrFunnet(fnr) }

            val automatiskKontorRutingService =  AutomatiskKontorRutingService(
            KontorTilordningService::tilordneKontor,
            { _, a, b-> KontorForGtNrFantDefaultKontor(kontor, b, a, GeografiskTilknytningBydelNr("3131")) },
            { AlderFunnet(40) },
            { ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER) },
            { SkjermingFunnet(HarSkjerming(false)) },
            { HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false)) },
            { AktivOppfolgingsperiode(fnr, oppfolgingsperiodeId, OffsetDateTime.now()) }
            )

            val tilordningProcessor = KontortilordningsProcessor(automatiskKontorRutingService)

            val leesahProcessor = LeesahProcessor(
                automatiskKontorRutingService,
                { FnrFunnet(fnr) },
                false,
            )

            val skjermingProcessor = SkjermingProcessor(
                automatiskKontorRutingService
            )

            val endringPaaOppfolgingsBrukerProcessor = EndringPaOppfolgingsBrukerProcessor()

            val topology = configureTopology(
                this.environment,
                TestLockProvider,
                CoroutineScope(Dispatchers.IO),
                sistePeriodeProcessor,
                tilordningProcessor,
                leesahProcessor,
                skjermingProcessor,
                endringPaaOppfolgingsBrukerProcessor
            )

            val (driver, inputTopics, _) = setupKafkaMock(topology,
                listOf("pto.siste-oppfolgingsperiode-v1"), null
            )

            inputTopics.first().pipeInput(aktorId, oppfolgingsperiodeMessage(
                aktorId = aktorId,
                oppfolgingsperiodeId = oppfolgingsperiodeId,
                startDato = ZonedDateTime.now(),
                sluttDato = null
            ))

            val aoKontor = transaction {
                ArbeidsOppfolgingKontorEntity.findById(fnr.value)
            }
            aoKontor shouldNotBe null
            aoKontor?.kontorId shouldBe "4154"
        }
    }

    @Test
    fun `app should not forward messages to KontorTilordning in prod`() = testApplication {
        val fnr = Fnr("22325678901")
        val aktorId = "22325678902"
        val kontor = KontorId("2232")
        val oppfolgingsperiodeId = OppfolgingsperiodeId(UUID.randomUUID())
        environment {
            config = ApplicationConfig("application.prod.yaml")
        }
        application {
            flywayMigrationInTest()
            val sistePeriodeProcessor = SisteOppfolgingsperiodeProcessor(
                OppfolgingsperiodeService,
                false
            ) { FnrFunnet(fnr) }

            val automatiskKontorRutingService =  AutomatiskKontorRutingService(
                KontorTilordningService::tilordneKontor,
                { _, a, b-> KontorForGtNrFantDefaultKontor(kontor, b, a, GeografiskTilknytningBydelNr("3131")) },
                { AlderFunnet(40) },
                { ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER) },
                { SkjermingFunnet(HarSkjerming(false)) },
                { HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false)) },
                { AktivOppfolgingsperiode(fnr, oppfolgingsperiodeId, OffsetDateTime.now()) }
            )

            val tilordningProcessor = KontortilordningsProcessor(automatiskKontorRutingService)

            val leesahProcessor = LeesahProcessor(
                automatiskKontorRutingService,
                { FnrFunnet(fnr) },
                false,
            )

            val skjermingProcessor = SkjermingProcessor(
                automatiskKontorRutingService
            )

            val endringPaaOppfolgingsBrukerProcessor = EndringPaOppfolgingsBrukerProcessor()

            val topology = configureTopology(
                this.environment,
                TestLockProvider,
                CoroutineScope(Dispatchers.IO),
                sistePeriodeProcessor,
                tilordningProcessor,
                leesahProcessor,
                skjermingProcessor,
                endringPaaOppfolgingsBrukerProcessor
            )

            val (driver, inputTopics, _) = setupKafkaMock(topology,
                listOf("pto.siste-oppfolgingsperiode-v1"), null
            )

            inputTopics.first().pipeInput(aktorId, oppfolgingsperiodeMessage(
                aktorId = aktorId,
                oppfolgingsperiodeId = oppfolgingsperiodeId,
                startDato = ZonedDateTime.now(),
                sluttDato = null
            ))

            val aoKontor = transaction {
                ArbeidsOppfolgingKontorEntity.findById(fnr.value)
            }
            aoKontor shouldBe null
        }
    }

    fun oppfolgingsperiodeMessage(
        oppfolgingsperiodeId: OppfolgingsperiodeId,
        startDato: ZonedDateTime,
        sluttDato: ZonedDateTime?,
        aktorId: String
    ): String {
        return """{"uuid":"${oppfolgingsperiodeId.value}", "startDato":"$startDato", "sluttDato":${sluttDato?.let { "\"$it\"" } ?: "null"}, "aktorId":"$aktorId"}"""
    }

}
