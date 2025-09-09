package kafka.consumers

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.testing.testApplication
import kafka.consumers.TopicUtils.oppfolgingStartetMelding
import kafka.consumers.TopicUtils.oppfolgingsperiodeMessage
import kafka.retry.TestLockProvider
import kafka.retry.library.internal.setupKafkaMock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import no.nav.db.AktorId
import no.nav.db.Fnr
import no.nav.db.entity.ArbeidsOppfolgingKontorEntity
import no.nav.db.entity.ArenaKontorEntity
import no.nav.db.entity.OppfolgingsperiodeEntity
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
import no.nav.services.OppfolgingsperiodeDao
import no.nav.utils.flywayMigrationInTest
import no.nav.utils.randomFnr
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import services.IdentService
import services.OppfolgingsperiodeService
import topics
import utils.Outcome
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.util.UUID

class BigAppTest {

    fun sisteOppfolgingsPeriodeProcessor(fnr: Fnr) = SisteOppfolgingsperiodeProcessor(
        OppfolgingsperiodeService(),
        false
    ) { IdentFunnet(fnr) }

    @Disabled
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
            val topics = environment.topics()
            val sistePeriodeProcessor = sisteOppfolgingsPeriodeProcessor(fnr)

            val oppfolgingsperiodeProvider: suspend (IdentResult) -> OppfolgingsperiodeOppslagResult  = { _: IdentResult -> AktivOppfolgingsperiode(fnr, oppfolgingsperiodeId, OffsetDateTime.now()) }
            val automatiskKontorRutingService =  AutomatiskKontorRutingService(
            KontorTilordningService::tilordneKontor,
            { _, a, b-> KontorForGtNrFantDefaultKontor(kontor, b, a, GeografiskTilknytningBydelNr("3131")) },
            { AlderFunnet(40) },
            { ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER) },
            { SkjermingFunnet(HarSkjerming(false)) },
            { HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false)) },
            oppfolgingsperiodeProvider,
                { _, _ -> Outcome.Success(false)  }
            )

            val tilordningProcessor = KontortilordningsProcessor(automatiskKontorRutingService)

            val leesahProcessor = LeesahProcessor(
                automatiskKontorRutingService,
                { IdentFunnet(fnr) },
                false,
            )

            val skjermingProcessor = SkjermingProcessor(automatiskKontorRutingService)

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
                identendringsProcessor,
                OppfolgingsHendelseProcessor(OppfolgingsperiodeService())
            )

            val (driver, inputTopics, _) = setupKafkaMock(topology,
                listOf(topics.inn.sisteOppfolgingsperiodeV1.name), null
            )

            val bruker = Bruker(fnr, aktorId.value, oppfolgingsperiodeId, ZonedDateTime.now())

            inputTopics.first().pipeInput(aktorId.value, oppfolgingsperiodeMessage(
                bruker = bruker
            ).value())

            val aoKontor = transaction {
                ArbeidsOppfolgingKontorEntity.findById(fnr.value)
            }
            aoKontor shouldNotBe null
            aoKontor?.kontorId shouldBe "4154"
        }
    }

    @Test
    fun `app should forward messages to KontorTilordning in prod, SisteOppfolgingsPeriode first`() = testApplication {
        val fnr = randomFnr()
        val aktorId = AktorId("4444447890245")
        val kontor = KontorId("2232")
        val oppfolgingsperiodeId = OppfolgingsperiodeId(UUID.randomUUID())
        environment {
            config = ApplicationConfig("application.prod.yaml")
        }
        application {
            flywayMigrationInTest()
            val topics = this.environment.topics()
            val sistePeriodeProcessor = sisteOppfolgingsPeriodeProcessor(fnr)

            val oppfolgingsperiodeProvider = { _: IdentResult -> AktivOppfolgingsperiode(fnr, oppfolgingsperiodeId, OffsetDateTime.now()) }
            val automatiskKontorRutingService =  AutomatiskKontorRutingService(
                KontorTilordningService::tilordneKontor,
                { _, a, b-> KontorForGtNrFantDefaultKontor(kontor, b, a, GeografiskTilknytningBydelNr("3131")) },
                { AlderFunnet(40) },
                { ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER) },
                { SkjermingFunnet(HarSkjerming(false)) },
                { HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false)) },
                oppfolgingsperiodeProvider,
                { _, _ -> Outcome.Success(false)  }
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
                identendringsProcessor,
                OppfolgingsHendelseProcessor(OppfolgingsperiodeService())
            )

            val (driver, inputTopics, _) = setupKafkaMock(topology,
                listOf(
                    topics.inn.sisteOppfolgingsperiodeV1.name,
                    topics.inn.oppfolgingsHendelser.name), null
            )

            val bruker = Bruker(fnr, aktorId.value, oppfolgingsperiodeId, ZonedDateTime.now())

            inputTopics.first().pipeInput(aktorId.value, oppfolgingsperiodeMessage(
                sluttDato = null,
                bruker = bruker,
            ).value())

            inputTopics.last().pipeInput(aktorId.value, oppfolgingStartetMelding(
                bruker = bruker,
            ).value())


            withClue("Skal finnes AO  kontor på bruker med fnr:${fnr.value}") {
                transaction {
                    ArbeidsOppfolgingKontorEntity.findById(fnr.value)
                } shouldNotBe null
            }
        }
    }


    @Test
    fun `app should forward messages to KontorTilordning in prod, oppfolgingsHendelse first`() = testApplication {
        val fnr = randomFnr()
        val aktorId = AktorId("4444447890246")
        val kontor = KontorId("2232")
        val oppfolgingsperiodeId = OppfolgingsperiodeId(UUID.randomUUID())
        environment {
            config = ApplicationConfig("application.prod.yaml")
        }
        application {
            flywayMigrationInTest()
            val topics = this.environment.topics()
            val sistePeriodeProcessor = sisteOppfolgingsPeriodeProcessor(fnr)

            val oppfolgingsperiodeProvider = { _: IdentResult -> AktivOppfolgingsperiode(fnr, oppfolgingsperiodeId, OffsetDateTime.now()) }
            val automatiskKontorRutingService =  AutomatiskKontorRutingService(
                KontorTilordningService::tilordneKontor,
                { _, a, b-> KontorForGtNrFantDefaultKontor(kontor, b, a, GeografiskTilknytningBydelNr("3131")) },
                { AlderFunnet(40) },
                { ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER) },
                { SkjermingFunnet(HarSkjerming(false)) },
                { HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false)) },
                oppfolgingsperiodeProvider,
                { _, _ -> Outcome.Success(false)  }
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
                identendringsProcessor,
                OppfolgingsHendelseProcessor(OppfolgingsperiodeService())
            )

            val (driver, inputTopics, _) = setupKafkaMock(topology,
                listOf(topics.inn.sisteOppfolgingsperiodeV1.name, topics.inn.oppfolgingsHendelser.name), null
            )

            val bruker = Bruker(fnr, aktorId.value, oppfolgingsperiodeId, ZonedDateTime.now())

            inputTopics.last().pipeInput(aktorId.value, oppfolgingStartetMelding(
                bruker = bruker,
            ).value())

            inputTopics.first().pipeInput(aktorId.value, oppfolgingsperiodeMessage(
                sluttDato = null,
                bruker = bruker,
            ).value())


            withClue("Skal finnes Oppfolgingsperiode på bruker med fnr:${fnr.value}") {
                transaction {
                    OppfolgingsperiodeEntity.findById(fnr.value)
                } shouldNotBe null
            }
            withClue("Skal finnes Arenakontor på bruker med fnr:${fnr.value}") {
                transaction {
                    ArenaKontorEntity.findById(fnr.value)
                } shouldNotBe null
            }
            withClue("Skal finnes AO kontor på bruker med fnr:${fnr.value}") {
                transaction {
                    ArbeidsOppfolgingKontorEntity.findById(fnr.value)
                } shouldNotBe null
            }

        }
    }
}
