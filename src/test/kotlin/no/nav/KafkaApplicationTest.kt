package no.nav.no.nav

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.ktor.server.testing.testApplication
import io.mockk.mockk
import kafka.consumers.TopicUtils
import kafka.retry.TestLockProvider
import kafka.retry.library.StreamType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import no.nav.db.Ident
import no.nav.db.Ident.HistoriskStatus.UKJENT
import no.nav.db.entity.ArbeidsOppfolgingKontorEntity
import no.nav.db.entity.ArenaKontorEntity
import no.nav.db.entity.GeografiskTilknyttetKontorEntity
import no.nav.db.entity.KontorHistorikkEntity
import no.nav.db.table.KontorhistorikkTable
import no.nav.domain.HarSkjerming
import no.nav.domain.HarStrengtFortroligAdresse
import no.nav.domain.KontorId
import no.nav.domain.OppfolgingsperiodeId
import no.nav.http.client.AlderFunnet
import no.nav.http.client.GeografiskTilknytningBydelNr
import no.nav.http.client.HarStrengtFortroligAdresseFunnet
import no.nav.http.client.IdenterFunnet
import no.nav.http.client.SkjermingFunnet
import no.nav.http.client.arbeidssogerregisteret.ProfileringFunnet
import no.nav.http.client.arbeidssogerregisteret.ProfileringsResultat
import no.nav.kafka.config.processorName
import no.nav.kafka.consumers.EndringPaOppfolgingsBrukerProcessor
import no.nav.kafka.config.streamsErrorHandlerConfig
import no.nav.kafka.consumers.FormidlingsGruppe
import no.nav.kafka.consumers.Kvalifiseringsgruppe
import no.nav.kafka.consumers.SkjermingProcessor
import no.nav.kafka.processor.ProcessRecord
import no.nav.kafka.retry.library.RetryConfig
import no.nav.kafka.retry.library.internal.RetryableRepository
import no.nav.kafka.retry.library.internal.RetryableProcessor
import no.nav.services.AktivOppfolgingsperiode
import no.nav.services.AutomatiskKontorRutingService
import domain.kontorForGt.KontorForGtFantDefaultKontor
import no.nav.services.KontorTilhorighetService
import no.nav.services.KontorTilordningService
import no.nav.utils.flywayMigrationInTest
import no.nav.utils.gittBrukerUnderOppfolging
import no.nav.utils.randomFnr
import no.nav.utils.randomTopicName
import org.apache.kafka.common.serialization.Serde
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.StreamsBuilder
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.TestInputTopic
import org.apache.kafka.streams.Topology
import org.apache.kafka.streams.TopologyTestDriver
import org.apache.kafka.streams.kstream.Consumed
import org.apache.kafka.streams.kstream.Named
import org.apache.kafka.streams.processor.api.ProcessorSupplier
import org.apache.kafka.streams.processor.api.Record
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import services.OppfolgingsperiodeService
import utils.Outcome
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.util.Properties
import java.util.UUID

class KafkaApplicationTest {

    @Test
    fun `skal lagre alle nye endringer på arena-kontor i historikk tabellen`() = testApplication {
        val topic = randomTopicName()
        val fnr = randomFnr()
        val oppfolgingsperiodeService = OppfolgingsperiodeService({ IdenterFunnet(listOf(fnr), fnr) },
            KontorTilordningService::slettArbeidsoppfølgingskontorTilordning)
        val endringPaOppfolgingsBrukerProcessor = EndringPaOppfolgingsBrukerProcessor(
            oppfolgingsperiodeService::getCurrentOppfolgingsperiode,
            { null },
            { KontorTilordningService.tilordneKontor(it, true)},
            { Result.success(Unit) },
            true
        )

        application {
            flywayMigrationInTest()
            gittBrukerUnderOppfolging(fnr)
            val topology = configureStringStringInputTopology(endringPaOppfolgingsBrukerProcessor::process, topic)
            val kafkaMockTopic = setupKafkaMock(topology, topic)
            kafkaMockTopic.pipeInput(
                fnr.value,
                endringPaOppfolgingsBrukerMessage(fnr, "1234", ZonedDateTime.parse("2025-08-13T13:01:14+02:00")).value()
            )
            kafkaMockTopic.pipeInput(
                fnr.value,
                endringPaOppfolgingsBrukerMessage(fnr, "4321", ZonedDateTime.parse("2025-08-14T13:01:14+02:00")).value()
            )
            transaction {
                withClue("") {
                    ArenaKontorEntity.findById(fnr.value)?.kontorId shouldBe "4321"
                }

                KontorHistorikkEntity
                    .find { KontorhistorikkTable.ident eq fnr.value }
                    .count() shouldBe 2
            }
        }
    }

    @Test
    fun `skal kun lagre nyere data i arena-kontor tabell og historikk tabellen`() = testApplication {
        val fnr = randomFnr()
        val topic = randomTopicName()
        val oppfolgingsperiodeService = OppfolgingsperiodeService({ IdenterFunnet(listOf(fnr), fnr) },
            KontorTilordningService::slettArbeidsoppfølgingskontorTilordning)
        val kontorTilhorighetService = KontorTilhorighetService(
            kontorNavnService = mockk(),
            poaoTilgangClient = mockk()
        ) { IdenterFunnet(listOf(fnr), fnr) }
        val endringPaOppfolgingsBrukerProcessor = EndringPaOppfolgingsBrukerProcessor(
            oppfolgingsperiodeService::getCurrentOppfolgingsperiode,
            { kontorTilhorighetService.getArenaKontorMedOppfolgingsperiode(it) },
            { KontorTilordningService.tilordneKontor(it, true)},
            { Result.success(Unit) },
            true
        )

        application {
            flywayMigrationInTest()
            gittBrukerUnderOppfolging(fnr)
            val topology = configureStringStringInputTopology(endringPaOppfolgingsBrukerProcessor::process, topic)

            val kafkaMockTopic = setupKafkaMock(topology, topic)
            kafkaMockTopic.pipeInput(
                fnr.value,
                endringPaOppfolgingsBrukerMessage(
                    fnr,
                    "1234",
                    ZonedDateTime.parse("2025-08-14T13:01:14+02:00")
                ).value(),
            )
            kafkaMockTopic.pipeInput(
                fnr.value,
                endringPaOppfolgingsBrukerMessage(
                    fnr,
                    "4321",
                    ZonedDateTime.parse("2025-08-13T13:01:14+02:00")
                ).value(),
            )
            transaction {
                ArenaKontorEntity.findById(fnr.value)?.kontorId shouldBe "1234"
                KontorHistorikkEntity
                    .find { KontorhistorikkTable.ident eq fnr.value }
                    .count() shouldBe 1
            }
        }
    }

    @Test
    fun `skal behandle endring i skjerming sett kontor fra GT`() = testApplication {
        val fnr =  randomFnr(UKJENT)
        val skjermetKontor = "4555"
        val topic = randomTopicName()
        val brukAoRuting = true

        val automatiskKontorRutingService = AutomatiskKontorRutingService(
            { _, b, a ->
                KontorForGtFantDefaultKontor(
                    KontorId(skjermetKontor),
                    a,
                    b,
                    GeografiskTilknytningBydelNr("3131")
                )
            },
            { AlderFunnet(40) },
            { ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER) },
            { SkjermingFunnet(HarSkjerming(false)) },
            { HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false)) },
            { AktivOppfolgingsperiode(fnr, OppfolgingsperiodeId(UUID.randomUUID()), OffsetDateTime.now()) },
            { _, _ -> Outcome.Success(false) }
        )
        val skjermingProcessor = SkjermingProcessor(automatiskKontorRutingService, brukAoRuting)

        application {
            flywayMigrationInTest()
            val topology = configureStringStringInputTopology(skjermingProcessor::process, topic)
            val kafkaMockTopic = setupKafkaMock(topology, topic)

            kafkaMockTopic.pipeInput(fnr.value, "true")

            transaction {
                GeografiskTilknyttetKontorEntity.findById(fnr.value)?.kontorId shouldBe skjermetKontor
                ArbeidsOppfolgingKontorEntity.findById(fnr.value)?.kontorId shouldBe skjermetKontor
                KontorHistorikkEntity
                    .find { KontorhistorikkTable.ident eq fnr.value }
                    .count() shouldBe 2
            }
        }
    }

    fun endringPaOppfolgingsBrukerMessage(
        ident: Ident,
        kontorId: String,
        sistEndretDato: ZonedDateTime
    ): Record<String, String> {
        return TopicUtils.endringPaaOppfolgingsBrukerMessage(
            ident,
            kontorId,
            sistEndretDato.toOffsetDateTime(),
            FormidlingsGruppe.ISERV,
            Kvalifiseringsgruppe.BATT
        )
    }

    fun <KIn, VIn, KOut, VOut> wrapInRetryProcessor(
        topic: String,
        keyInSerde: Serde<KIn>,
        valueInSerde: Serde<VIn>,
        processRecord: ProcessRecord<KIn, VIn, KOut, VOut>,
        streamType: StreamType = StreamType.SOURCE
    ): ProcessorSupplier<KIn, VIn, KOut, VOut> {
        val testRepository = RetryableRepository(topic)
        return ProcessorSupplier {
            RetryableProcessor(
                config = RetryConfig(),
                keyInSerde = keyInSerde,
                valueInSerde = valueInSerde,
                topic = topic,
                streamType = streamType,
                repository = testRepository,
                businessLogic = processRecord,
                lockProvider = TestLockProvider,
                punctuationCoroutineScope = CoroutineScope(Dispatchers.IO)
            )
        }
    }

    private fun configureStringStringInputTopology(
        processRecord: ProcessRecord<String, String, String, String>,
        topic: String
    ): Topology {
        val builder = StreamsBuilder()
        val testSupplier = wrapInRetryProcessor(
            topic = topic,
            keyInSerde = Serdes.String(),
            valueInSerde = Serdes.String(),
            processRecord = processRecord,
        )

        builder.stream(topic, Consumed.with(Serdes.String(), Serdes.String()))
            .process(testSupplier, Named.`as`(processorName(topic)))
        return builder.build()
    }
}

private fun setupKafkaMock(topology: Topology, topic: String): TestInputTopic<String, String> {
    val props = Properties()
    props.setProperty(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9091")
    props.setProperty(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String()::class.java.name)
    props.setProperty(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String()::class.java.name)
    props.streamsErrorHandlerConfig()
    val driver = TopologyTestDriver(topology, props)
    return driver.createInputTopic(topic, Serdes.String().serializer(), Serdes.String().serializer())
}
