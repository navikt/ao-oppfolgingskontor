package no.nav.no.nav

import domain.IdenterFunnet
import domain.kontorForGt.KontorForGtFantDefaultKontor
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.ktor.server.testing.testApplication
import io.mockk.mockk
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.util.Properties
import java.util.UUID
import kafka.consumers.TopicUtils
import kafka.producers.OppfolgingEndretTilordningMelding
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
import no.nav.db.table.FailedMessagesEntity
import no.nav.db.table.KontorhistorikkTable
import no.nav.domain.HarSkjerming
import no.nav.domain.HarStrengtFortroligAdresse
import no.nav.domain.KontorId
import no.nav.domain.OppfolgingsperiodeId
import no.nav.http.client.AlderFunnet
import no.nav.http.client.GeografiskTilknytningBydelNr
import no.nav.http.client.HarStrengtFortroligAdresseFunnet
import no.nav.http.client.SkjermingFunnet
import no.nav.http.client.arbeidssogerregisteret.ProfileringFunnet
import no.nav.http.client.arbeidssogerregisteret.ProfileringsResultat
import no.nav.kafka.config.processorName
import no.nav.kafka.config.streamsErrorHandlerConfig
import no.nav.kafka.consumers.SkjermingProcessor
import no.nav.kafka.processor.ProcessRecord
import no.nav.kafka.retry.library.RetryConfig
import no.nav.kafka.retry.library.internal.RetryableProcessor
import no.nav.kafka.retry.library.internal.RetryableRepository
import no.nav.services.AktivOppfolgingsperiode
import no.nav.services.AutomatiskKontorRutingService
import no.nav.services.KontorTilhorighetService
import no.nav.services.NotUnderOppfolging
import no.nav.services.OppfolgingsperiodeOppslagResult
import no.nav.utils.flywayMigrationInTest
import no.nav.utils.gittBrukerUnderOppfolging
import no.nav.utils.kontorTilordningService
import no.nav.utils.randomFnr
import no.nav.utils.randomInternIdent
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
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.core.eq
import org.junit.jupiter.api.Test
import services.OppfolgingsperiodeService
import utils.Outcome

class KafkaApplicationTest {

    @Test
    fun `skal behandle endring i skjerming sett kontor fra GT`() = testApplication {
        val fnr =  randomFnr(UKJENT)
        val skjermetKontor = "4555"
        val topic = randomTopicName()
        val oppfølgingsperiodeId = OppfolgingsperiodeId(UUID.randomUUID())

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
            { AktivOppfolgingsperiode(fnr, randomInternIdent(),  oppfølgingsperiodeId, OffsetDateTime.now()) },
            { _, _ -> Outcome.Success(false) },
            { null },
        )
        val skjermingProcessor = SkjermingProcessor(
            automatiskKontorRutingService,
            kontorTilordningService,
        )

        application {
            flywayMigrationInTest()
            gittBrukerUnderOppfolging(fnr, oppfølgingsperiodeId)
            val topology = configureStringOptionalStringInputTopology(skjermingProcessor::process, topic)
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

    @Test
    fun `skal ignorere melding om skjerming for bruker som ikke er under oppfølging`() = testApplication {
        val fnr =  randomFnr(UKJENT)
        val skjermetKontor = "4555"
        val topic = randomTopicName()

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
            { SkjermingFunnet(HarSkjerming(true)) },
            { HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false)) },
            { NotUnderOppfolging },
            { _, _ -> Outcome.Success(false) },
            { null },
        )
        val skjermingProcessor = SkjermingProcessor(
            automatiskKontorRutingService,
            kontorTilordningService,
        )

        application {
            flywayMigrationInTest()
            val topology = configureStringOptionalStringInputTopology(skjermingProcessor::process, topic)
            val kafkaMockTopic = setupKafkaMock(topology, topic)

            kafkaMockTopic.pipeInput(fnr.value, "true")

            transaction {
                GeografiskTilknyttetKontorEntity.findById(fnr.value)?.kontorId shouldBe null
                ArbeidsOppfolgingKontorEntity.findById(fnr.value)?.kontorId shouldBe null
                KontorHistorikkEntity
                    .find { KontorhistorikkTable.ident eq fnr.value }
                    .count() shouldBe 0
                FailedMessagesEntity.all().filter { it.topic == topic }.count() shouldBe 0
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
        processRecord: ProcessRecord<String, String, OppfolgingsperiodeId, OppfolgingEndretTilordningMelding>,
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

    private fun configureStringOptionalStringInputTopology(
        processRecord: ProcessRecord<String, String?, OppfolgingsperiodeId, OppfolgingEndretTilordningMelding>,
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
