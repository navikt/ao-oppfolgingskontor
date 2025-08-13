package no.nav.no.nav

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.ktor.server.testing.testApplication
import kafka.consumers.SisteOppfolgingsperiodeProcessor
import kafka.retry.TestLockProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import no.nav.db.AktorId
import no.nav.db.Fnr
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
import no.nav.http.client.IdentFunnet
import no.nav.http.client.GeografiskTilknytningBydelNr
import no.nav.http.client.HarStrengtFortroligAdresseFunnet
import no.nav.http.client.SkjermingFunnet
import no.nav.http.client.arbeidssogerregisteret.ProfileringFunnet
import no.nav.http.client.arbeidssogerregisteret.ProfileringsResultat
import no.nav.kafka.config.processorName
import no.nav.kafka.consumers.EndringPaOppfolgingsBrukerProcessor
import no.nav.kafka.config.streamsErrorHandlerConfig
import no.nav.kafka.consumers.KontortilordningsProcessor
import no.nav.kafka.consumers.SkjermingProcessor
import no.nav.kafka.processor.ProcessRecord
import no.nav.kafka.retry.library.RetryConfig
import no.nav.kafka.retry.library.internal.RetryableRepository
import no.nav.kafka.retry.library.internal.RetryableProcessor
import no.nav.services.AktivOppfolgingsperiode
import no.nav.services.AutomatiskKontorRutingService
import no.nav.services.KontorForGtNrFantDefaultKontor
import no.nav.services.KontorTilordningService
import no.nav.services.OppfolgingsperiodeService
import no.nav.utils.flywayMigrationInTest
import no.nav.utils.gittBrukerUnderOppfolging
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
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.util.Properties
import java.util.UUID

class KafkaApplicationTest {
    val topic = "test-topic"
    val endringPaOppfolgingsBrukerProcessor = EndringPaOppfolgingsBrukerProcessor()

    @Test
    fun `skal lagre alle nye endringer på arena-kontor i historikk tabellen`() = testApplication {
        val fnr = "12345768901"

        application {
            flywayMigrationInTest()
            gittBrukerUnderOppfolging(Fnr(fnr))
            val topology = configureStringStringInputTopology(endringPaOppfolgingsBrukerProcessor::process)
            val kafkaMockTopic = setupKafkaMock(topology, topic)
            kafkaMockTopic.pipeInput(
                fnr,
                endringPaOppfolgingsBrukerMessage("1234", ZonedDateTime.parse("2025-04-10T13:01:14+02:00"))
            )
            kafkaMockTopic.pipeInput(
                fnr,
                endringPaOppfolgingsBrukerMessage("4321", ZonedDateTime.parse("2025-05-10T13:01:14+02:00"))
            )
            transaction {
                ArenaKontorEntity.Companion.findById(fnr)?.kontorId shouldBe "4321"
                KontorHistorikkEntity.Companion
                    .find { KontorhistorikkTable.fnr eq fnr }
                    .count() shouldBe 2
            }
        }
    }

    @Test
    fun `skal tilordne kontor til brukere som har fått startet oppfølging`() = testApplication {
        val fnr = Fnr("22325678901")
        val kontor = KontorId("2228")

        application {
            flywayMigrationInTest()
            val aktorId = AktorId("1234567890123")
            val periodeStart = ZonedDateTime.now().minusDays(2)
            val oppfolgingsperiodeId = OppfolgingsperiodeId(UUID.randomUUID())

            val sistePeriodeProcessor = SisteOppfolgingsperiodeProcessor(
                OppfolgingsperiodeService,
                false
            ) { IdentFunnet(fnr) }
            val tilordningProcessor = KontortilordningsProcessor(AutomatiskKontorRutingService(
                KontorTilordningService::tilordneKontor,
                { _, a, b-> KontorForGtNrFantDefaultKontor(kontor, b, a, GeografiskTilknytningBydelNr("3131")) },
                { AlderFunnet(40) },
                { ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER) },
                { SkjermingFunnet(HarSkjerming(false)) },
                { HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false)) },
                { AktivOppfolgingsperiode(fnr, oppfolgingsperiodeId, OffsetDateTime.now()) }),
            )

            val builder = StreamsBuilder()
            val sistePeriodeProcessorSupplier = wrapInRetryProcessor(
                topic = topic,
                keyInSerde = Serdes.String(),
                valueInSerde = Serdes.String(),
                processRecord = sistePeriodeProcessor::process,
            )
            val tilordningProcessorSupplier = wrapInRetryProcessor(
                topic = topic,
                keyInSerde = KontortilordningsProcessor.identSerde,
                valueInSerde = KontortilordningsProcessor.oppfolgingsperiodeStartetSerde,
                processRecord = tilordningProcessor::process,
            )
            builder.stream(topic, Consumed.with(Serdes.String(), Serdes.String()))
                .process(sistePeriodeProcessorSupplier, Named.`as`(processorName(topic)))
                .process(tilordningProcessorSupplier)
            val topology = builder.build()

            val kafkaMockTopic = setupKafkaMock(topology, topic)
            kafkaMockTopic.pipeInput(
                aktorId.value,
                oppfolgingsperiodeMessage(oppfolgingsperiodeId, periodeStart, null, aktorId.value)
            )
            transaction {
                ArbeidsOppfolgingKontorEntity.Companion.findById(fnr.value)?.kontorId shouldBe "4154"
                KontorHistorikkEntity.Companion
                    .find { KontorhistorikkTable.fnr eq fnr.value }
                    .count().let {
                        withClue("Antall historikkinnslag skal være 1") {
                            it shouldBe 1
                        }
                    }
            }
        }
    }

    @Test
    fun `skal kun lagre nyere data i arena-kontor tabell og historikk tabellen`() = testApplication {
        val fnr = "52345678901"

        application {
            flywayMigrationInTest()
            gittBrukerUnderOppfolging(Fnr(fnr))
            val topology = configureStringStringInputTopology(endringPaOppfolgingsBrukerProcessor::process)

            val kafkaMockTopic = setupKafkaMock(topology, topic)
            kafkaMockTopic.pipeInput(
                fnr, endringPaOppfolgingsBrukerMessage("1234", ZonedDateTime.parse("2025-04-10T13:01:14+02:00"))
            )
            kafkaMockTopic.pipeInput(
                fnr, endringPaOppfolgingsBrukerMessage("4321", ZonedDateTime.parse("2025-03-10T13:01:14+02:00"))
            )
            transaction {
                ArenaKontorEntity.findById(fnr)?.kontorId shouldBe "1234"
                KontorHistorikkEntity
                    .find { KontorhistorikkTable.fnr eq fnr }
                    .count() shouldBe 1
            }
        }
    }

    @Test
    fun `skal behandle endring i skjerming sett kontor fra GT`() = testApplication {
        val fnr = Fnr("55345678901")
        val skjermetKontor = "4555"

        val automatiskKontorRutingService = AutomatiskKontorRutingService(
            KontorTilordningService::tilordneKontor,
            { _, b, a-> KontorForGtNrFantDefaultKontor(KontorId(skjermetKontor), a, b, GeografiskTilknytningBydelNr("3131")) },
            { AlderFunnet(40) },
            { ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER) },
            { SkjermingFunnet(HarSkjerming(false)) },
            { HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false)) },
            { AktivOppfolgingsperiode(fnr, OppfolgingsperiodeId(UUID.randomUUID()), OffsetDateTime.now()) }
        )
        val skjermingProcessor = SkjermingProcessor(automatiskKontorRutingService)

        application {
            flywayMigrationInTest()
            val topology = configureStringStringInputTopology(skjermingProcessor::process)
            val kafkaMockTopic = setupKafkaMock(topology, topic)

            kafkaMockTopic.pipeInput(fnr.value, "true")

            transaction {
                GeografiskTilknyttetKontorEntity.Companion.findById(fnr.value)?.kontorId shouldBe skjermetKontor
                ArbeidsOppfolgingKontorEntity.Companion.findById(fnr.value)?.kontorId shouldBe skjermetKontor
                KontorHistorikkEntity.Companion
                    .find { KontorhistorikkTable.fnr eq fnr.value }
                    .count() shouldBe 2
            }
        }
    }

//    @Test
    fun testKafkaRetry() = testApplication {
        val fnr = "12345678901"

        application {
            val dataSource = flywayMigrationInTest()

            val topology = configureStringStringInputTopology(endringPaOppfolgingsBrukerProcessor::process)
            val kafkaMockTopic = setupKafkaMock(topology, topic)
            kafkaMockTopic.pipeInput(fnr, endringPaOppfolgingsBrukerMessage("1234", ZonedDateTime.now()))
        }
    }

//    @Test
    fun testKafkaSkipMessage() = testApplication {
        val fnr = "12345678901"
        application {
            val dataSource = flywayMigrationInTest()

            val topology = configureStringStringInputTopology(endringPaOppfolgingsBrukerProcessor::process)
            val kafkaMockTopic = setupKafkaMock(topology, topic)
            kafkaMockTopic.pipeInput(fnr, """{"oppfolgingsenhet":"ugyldigEnhet"}""")
        }
    }

    fun endringPaOppfolgingsBrukerMessage(kontorId: String, sistEndretDato: ZonedDateTime): String {
        return """{"oppfolgingsenhet":"$kontorId", "sistEndretDato": "$sistEndretDato" }"""
    }

    fun oppfolgingsperiodeMessage(
        oppfolgingsperiodeId: OppfolgingsperiodeId,
        startDato: ZonedDateTime,
        sluttDato: ZonedDateTime?,
        aktorId: String
    ): String {
        return """{"uuid":"${oppfolgingsperiodeId.value}", "startDato":"$startDato", "sluttDato":${sluttDato?.let { "\"$it\"" } ?: "null"}, "aktorId":"$aktorId"}"""
    }

    fun <KIn, VIn, KOut, VOut> wrapInRetryProcessor(
        topic: String,
        keyInSerde: Serde<KIn>,
        valueInSerde: Serde<VIn>,
        processRecord: ProcessRecord<KIn ,VIn, KOut, VOut>
    ): ProcessorSupplier<KIn, VIn, KOut, VOut> {
        val testRepository = RetryableRepository(topic)
        return ProcessorSupplier {
            RetryableProcessor(
                config = RetryConfig(),
                keyInSerde = keyInSerde,
                valueInSerde = valueInSerde,
                topic = topic,
                repository = testRepository,
                businessLogic = processRecord,
                lockProvider = TestLockProvider,
                punctuationCoroutineScope = CoroutineScope(Dispatchers.IO)
            )
        }
    }

    private fun configureStringStringInputTopology(processRecord: ProcessRecord<String, String, String, String>): Topology {
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
