package kafka.consumers

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.testing.testApplication
import kafka.retry.TestLockProvider
import kafka.retry.library.internal.setupKafkaMock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import no.nav.db.AktorId
import no.nav.db.Fnr
import no.nav.db.entity.ArbeidsOppfolgingKontorEntity
import no.nav.db.entity.ArenaKontorEntity
import no.nav.domain.HarSkjerming
import no.nav.domain.HarStrengtFortroligAdresse
import no.nav.domain.KontorId
import no.nav.domain.OppfolgingsperiodeId
import no.nav.http.client.AlderFunnet
import no.nav.http.client.IdentFunnet
import no.nav.http.client.GeografiskTilknytningBydelNr
import no.nav.http.client.HarStrengtFortroligAdresseFunnet
import no.nav.http.client.IdentResult
import no.nav.http.client.IdenterFunnet
import no.nav.http.client.SkjermingFunnet
import no.nav.http.client.arbeidssogerregisteret.ProfileringFunnet
import no.nav.http.client.arbeidssogerregisteret.ProfileringsResultat
import no.nav.kafka.config.configureTopology
import no.nav.kafka.consumers.EndringPaOppfolgingsBrukerProcessor
import no.nav.kafka.consumers.KontortilordningsProcessor
import no.nav.kafka.consumers.LeesahProcessor
import no.nav.kafka.consumers.SkjermingProcessor
import no.nav.services.AktivOppfolgingsperiode
import no.nav.services.AutomatiskKontorRutingService
import no.nav.services.KontorForGtNrFantDefaultKontor
import no.nav.services.KontorTilordningService
import no.nav.services.OppfolgingsperiodeOppslagResult
import no.nav.services.OppfolgingsperiodeService
import no.nav.utils.flywayMigrationInTest
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import services.IdentService
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.util.UUID

class BigAppTest {

    @Test
    fun `app should forward from SisteOppfolgingsperiodeProcessor to KontortilordningsProcessor if not prod`() = testApplication {
        val fnr = Fnr("22325678901")
        val aktorId = AktorId("2232567890233")
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
            ) { IdentFunnet(fnr) }


            val oppfolgingsperiodeProvider: suspend (IdentResult) -> OppfolgingsperiodeOppslagResult  = { _: IdentResult -> AktivOppfolgingsperiode(fnr, oppfolgingsperiodeId, OffsetDateTime.now()) }
            val automatiskKontorRutingService =  AutomatiskKontorRutingService(
            KontorTilordningService::tilordneKontor,
            { _, a, b-> KontorForGtNrFantDefaultKontor(kontor, b, a, GeografiskTilknytningBydelNr("3131")) },
            { AlderFunnet(40) },
            { ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER) },
            { SkjermingFunnet(HarSkjerming(false)) },
            { HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false)) },
            oppfolgingsperiodeProvider
            )

            val tilordningProcessor = KontortilordningsProcessor(automatiskKontorRutingService)

            val leesahProcessor = LeesahProcessor(
                automatiskKontorRutingService,
                { IdentFunnet(fnr) },
                false,
            )

            val skjermingProcessor = SkjermingProcessor(
                automatiskKontorRutingService
            )

            val endringPaaOppfolgingsBrukerProcessor = EndringPaOppfolgingsBrukerProcessor(
                ArenaKontorEntity::sisteLagreKontorArenaKontor,
                oppfolgingsperiodeProvider
            )

            val identService = IdentService { IdenterFunnet(emptyList(), "") }
            val identendringsProcessor = IdentChangeProcessor(identService)

            val topology = configureTopology(
                this.environment,
                TestLockProvider,
                CoroutineScope(Dispatchers.IO),
                sistePeriodeProcessor,
                tilordningProcessor,
                leesahProcessor,
                skjermingProcessor,
                endringPaaOppfolgingsBrukerProcessor,
                identendringsProcessor
            )

            val (driver, inputTopics, _) = setupKafkaMock(topology,
                listOf("pto.siste-oppfolgingsperiode-v1"), null
            )

            inputTopics.first().pipeInput(aktorId.value, oppfolgingsperiodeMessage(
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

    // aa in name to make this test run first because something is wrong when this test runs first
    @Test
    fun `aa - app should not forward messages to KontorTilordning in prod`() = testApplication {
        val fnr = Fnr("01121678901")
        val aktorId = AktorId("4444447890244")
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
            ) { IdentFunnet(fnr) }

            val oppfolgingsperiodeProvider = { _: IdentResult -> AktivOppfolgingsperiode(fnr, oppfolgingsperiodeId, OffsetDateTime.now()) }
            val automatiskKontorRutingService =  AutomatiskKontorRutingService(
                KontorTilordningService::tilordneKontor,
                { _, a, b-> KontorForGtNrFantDefaultKontor(kontor, b, a, GeografiskTilknytningBydelNr("3131")) },
                { AlderFunnet(40) },
                { ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER) },
                { SkjermingFunnet(HarSkjerming(false)) },
                { HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false)) },
                oppfolgingsperiodeProvider
            )

            val tilordningProcessor = KontortilordningsProcessor(automatiskKontorRutingService)

            val leesahProcessor = LeesahProcessor(
                automatiskKontorRutingService,
                { IdentFunnet(fnr) },
                false,
            )

            val skjermingProcessor = SkjermingProcessor(
                automatiskKontorRutingService
            )

            val endringPaaOppfolgingsBrukerProcessor = EndringPaOppfolgingsBrukerProcessor(
                ArenaKontorEntity::sisteLagreKontorArenaKontor,
                oppfolgingsperiodeProvider
            )

            val identService = IdentService { IdenterFunnet(emptyList(), "") }
            val identendringsProcessor = IdentChangeProcessor(identService)

            val topology = configureTopology(
                this.environment,
                TestLockProvider,
                CoroutineScope(Dispatchers.IO),
                sistePeriodeProcessor,
                tilordningProcessor,
                leesahProcessor,
                skjermingProcessor,
                endringPaaOppfolgingsBrukerProcessor,
                identendringsProcessor
            )

            val (driver, inputTopics, _) = setupKafkaMock(topology,
                listOf("pto.siste-oppfolgingsperiode-v1"), null
            )

            inputTopics.first().pipeInput(aktorId.value, oppfolgingsperiodeMessage(
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
        aktorId: AktorId,
    ): String {
        return """{"uuid":"${oppfolgingsperiodeId.value}", "startDato":"$startDato", "sluttDato":${sluttDato?.let { "\"$it\"" } ?: "null"}, "aktorId":"${aktorId.value}"}"""
    }

}
