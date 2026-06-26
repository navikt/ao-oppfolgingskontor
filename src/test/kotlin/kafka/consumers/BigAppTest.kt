package kafka.consumers

import db.table.KafkaOffsetTable
import domain.kontorForGt.KontorForGtFantDefaultKontor
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.server.application.*
import io.ktor.server.config.*
import io.ktor.server.testing.*
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kafka.consumers.TopicUtils.oppfolgingAvsluttetMelding
import kafka.consumers.TopicUtils.oppfolgingStartetMelding
import kafka.producers.KontorEndringProducer
import kafka.producers.OppfolgingEndretTilordningMelding
import kafka.retry.TestLockProvider
import kafka.retry.library.internal.setupKafkaMock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import no.nav.db.AktorId
import no.nav.db.Ident
import no.nav.db.IdentSomKanLagres
import no.nav.db.entity.ArbeidsOppfolgingKontorEntity
import no.nav.db.entity.KontorHistorikkEntity
import no.nav.db.entity.OppfolgingsperiodeEntity
import no.nav.db.table.KontorhistorikkTable
import no.nav.domain.*
import no.nav.http.client.*
import no.nav.http.client.arbeidssogerregisteret.ProfileringFunnet
import no.nav.http.client.arbeidssogerregisteret.ProfileringsResultat
import no.nav.kafka.config.configureTopology
import no.nav.kafka.consumers.KontortilordningsProcessor
import no.nav.kafka.consumers.LeesahProcessor
import no.nav.kafka.consumers.SkjermingProcessor
import no.nav.services.AktivOppfolgingsperiode
import no.nav.services.AutomatiskKontorRutingService
import no.nav.utils.*
import org.apache.kafka.streams.Topology
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import services.IdentService
import services.OppfolgingsperiodeService
import topics
import utils.Outcome
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.util.*

class BigAppTest {
    private val kontorEndringProducer = mockk<KontorEndringProducer>()

    @BeforeEach
    fun reset() {
        flywayMigrationInTest()
        transaction {
            KafkaOffsetTable.deleteAll()
        }
        clearMocks(kontorEndringProducer)
        coEvery { kontorEndringProducer.publiserEndringPåKontor(any<OppfolgingEndretTilordningMelding>()) } returns Result.success(
            Unit
        )
    }

    @Test
    fun `app should forward messages to KontorTilordning`() = testApplication {
        val fnr = randomFnr()
        val aktorId = AktorId("4444447890246", Ident.HistoriskStatus.AKTIV)
        val kontor = KontorId("2232")
        val oppfolgingsperiodeId = OppfolgingsperiodeId(UUID.randomUUID())
        environment {
            config = ApplicationConfig("application.prod.yaml")
        }
        application {
            val topics = this.environment.topics()
            val topology = setupTestEnvironment(
                fnr,
                oppfolgingsperiodeId,
                kontorEndringProducer,
                kontor,
                HarSkjerming(false),
            )

            val (_, inputTopics, _) = setupKafkaMock(
                topology,
                listOf(topics.inn.oppfolgingsHendelser.name), null
            )
            val bruker = Bruker(fnr, aktorId.value, oppfolgingsperiodeId, ZonedDateTime.now())

            inputTopics.first().pipeInput(
                fnr.value, oppfolgingStartetMelding(
                    bruker = bruker,
                ).value()
            )

            withClue("Skal finnes Oppfolgingsperiode på bruker") {
                transaction {
                    OppfolgingsperiodeEntity.findById(fnr.value)
                } shouldNotBe null
            }
//            withClue("Skal finnes Arenakontor på bruker") {
//                transaction {
//                    ArenaKontorEntity.findById(fnr.value)
//                } shouldNotBe null
//            }
            withClue("Skal finnes AO kontor på bruker") {
                transaction {
                    ArbeidsOppfolgingKontorEntity.findById(fnr.value)
                } shouldNotBe null
            }
            val antallHistorikkRader = transaction {
                KontorHistorikkEntity.find { KontorhistorikkTable.ident eq fnr.value }.count()
            }
            withClue("Skal finnes 2 historikkinnslag på bruker men var $antallHistorikkRader") {
                antallHistorikkRader shouldBe 2
            }
            coVerify {
                kontorEndringProducer.publiserEndringPåKontor(
                    OppfolgingEndretTilordningMelding(
                        "4154",
                        oppfolgingsperiodeId.value.toString(),
                        fnr.value,
                        KontorEndringsType.AutomatiskRutetTilNOE
                    )
                )
            }
        }
    }

    @Test
    fun `skal rulle tilbake databaseendringer hvis skriving til kafka feiler`() = testApplication {
        val fnr = randomFnr()
        val aktorId = AktorId("4444447890246", Ident.HistoriskStatus.AKTIV)
        val kontor = KontorId("2232")
        val oppfolgingsperiodeId = OppfolgingsperiodeId(UUID.randomUUID())
        coEvery { kontorEndringProducer.publiserTombstone(any()) } returns Result.failure(
            RuntimeException("Feil ved publisering av tombstone")
        )

        environment {
            config = ApplicationConfig("application.prod.yaml")
        }
        application {
            gittBrukerUnderOppfolging(
                fnr = fnr,
                oppfolgingsperiodeId = oppfolgingsperiodeId,
            )
            gittIdentMedKontor(
                ident = fnr,
                kontorId = kontor,
                oppfolgingsperiodeId = oppfolgingsperiodeId,
            )
            val topics = this.environment.topics()
            val topology = setupTestEnvironment(
                fnr,
                oppfolgingsperiodeId,
                kontorEndringProducer,
                kontor,
                HarSkjerming(false),
            )

            val (_, inputTopics, _) = setupKafkaMock(
                topology,
                listOf(topics.inn.oppfolgingsHendelser.name), null
            )
            val bruker = Bruker(fnr, aktorId.value, oppfolgingsperiodeId, ZonedDateTime.now())

            inputTopics.first().pipeInput(
                fnr.value, oppfolgingAvsluttetMelding(
                    bruker = bruker,
                    sluttDato = ZonedDateTime.now(),
                ).value()
            )

            withClue("Skal fortsatt finnes Oppfolgingsperiode på bruker") {
                transaction {
                    OppfolgingsperiodeEntity.findById(fnr.value)
                } shouldNotBe null
            }
            withClue("Skal fortsatt finnes AO kontor på bruker") {
                transaction {
                    ArbeidsOppfolgingKontorEntity.findById(fnr.value)
                } shouldNotBe null
            }
        }
    }

    @Test
    fun `skal forwarde oppdatert ao-kontor til kafkaproducer ved endring av skjerming`() = testApplication {
        val fnr = randomFnr()
        val kontor = KontorId("2232")
        val skjermetKontor = KontorId("0283")
        val oppfolgingsperiodeId = OppfolgingsperiodeId(UUID.randomUUID())
        environment {
            config = ApplicationConfig("application.prod.yaml")
        }
        application {
            gittBrukerUnderOppfolging(
                fnr = fnr,
                oppfolgingsperiodeId = oppfolgingsperiodeId,
            )
            gittIdentMedKontor(
                ident = fnr,
                kontorId = kontor,
                oppfolgingsperiodeId = oppfolgingsperiodeId,
            )
            val topics = this.environment.topics()
            val topology = setupTestEnvironment(
                fnr,
                oppfolgingsperiodeId,
                kontorEndringProducer,
                skjermetKontor,
                HarSkjerming(true),
            )
            val (_, inputTopics, _) = setupKafkaMock(
                topology,
                listOf(topics.inn.skjerming.name), null
            )
            inputTopics.first().pipeInput(
                fnr.value, true.toString()
            )

            withClue("Skal oppdatere AO-kontor på bruker") {
                transaction {
                    ArbeidsOppfolgingKontorEntity.findById(fnr.value)
                }?.kontorId shouldBe skjermetKontor.id
            }
            val antallHistorikkRader = transaction {
                KontorHistorikkEntity.find { KontorhistorikkTable.ident eq fnr.value }.count()
            }
            withClue("Skal finnes 3 historikkinnslag på bruker men var $antallHistorikkRader") {
                antallHistorikkRader shouldBe 3
            }
            coVerify {
                kontorEndringProducer.publiserEndringPåKontor(
                    OppfolgingEndretTilordningMelding(
                        skjermetKontor.id,
                        oppfolgingsperiodeId.value.toString(),
                        fnr.value,
                        KontorEndringsType.FikkSkjerming
                    )
                )
            }
        }
    }

    private fun Application.setupTestEnvironment(
        fnr: IdentSomKanLagres,
        oppfolgingsperiodeId: OppfolgingsperiodeId,
        kontorEndringProducer: KontorEndringProducer,
        kontor: KontorId,
        harSkjerming: HarSkjerming,
    ): Topology {
        val oppfolgingsperiodeProvider =
            { _: Ident -> AktivOppfolgingsperiode(fnr, randomInternIdent(), oppfolgingsperiodeId, OffsetDateTime.now()) }
        val automatiskKontorRutingService = AutomatiskKontorRutingService(
            { _, a, b -> KontorForGtFantDefaultKontor(kontor, harSkjerming, a, GeografiskTilknytningBydelNr("3131")) },
            { AlderFunnet(40) },
            { ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER) },
            { SkjermingFunnet(harSkjerming) },
            { HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false)) },
            oppfolgingsperiodeProvider,
            { _, _ -> Outcome.Success(false) },
            { null },
        )
        val tilordningProcessor = KontortilordningsProcessor(automatiskKontorRutingService, kontorTilordningService)
        val leesahProcessor = LeesahProcessor(
            automatiskKontorRutingService,
            kontorTilordningService,
        )
        val skjermingProcessor = SkjermingProcessor(
            automatiskKontorRutingService,
            kontorTilordningService,
        )
        val identService = IdentService { PdlIdenterFunnet(listOf(fnr), fnr) }
        val identendringsProcessor = IdentChangeProcessor(identService)

        val publiserKontorTilordningProcessor = PubliserKontorTilordningProcessor(
            hentAlleIdenter = identService::hentAlleIdenter,
            publiserKontorTilordning = kontorEndringProducer::publiserEndringPåKontor,
        )
        return configureTopology(
            this.environment,
            TestLockProvider,
            CoroutineScope(Dispatchers.IO),
            tilordningProcessor,
            publiserKontorTilordningProcessor,
            leesahProcessor,
            skjermingProcessor,
            identendringsProcessor,
            OppfolgingsHendelseProcessor(
                OppfolgingsperiodeService(identService::hentAlleIdenter, kontorTilordningService::slettArbeidsoppfølgingskontorTilordning),
                kontorEndringProducer::publiserTombstone,
            ),
        )
    }
}
