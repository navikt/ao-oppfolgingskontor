package no.nav.no.nav

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.ktor.server.testing.testApplication
import no.nav.db.entity.ArbeidsOppfolgingKontorEntity
import no.nav.db.entity.ArenaKontorEntity
import no.nav.db.entity.KontorHistorikkEntity
import no.nav.db.table.KontorhistorikkTable
import no.nav.domain.KontorId
import no.nav.http.client.AlderFunnet
import no.nav.http.client.FnrFunnet
import no.nav.http.client.poaoTilgang.GTKontorFunnet
import no.nav.http.client.arbeidssogerregisteret.ProfileringsResultat
import no.nav.kafka.consumers.EndringPaOppfolgingsBrukerConsumer
import no.nav.kafka.config.configureTopology
import no.nav.kafka.config.streamsErrorHandlerConfig
import no.nav.kafka.consumers.OppfolgingsPeriodeConsumer
import no.nav.services.AutomatiskKontorRutingService
import no.nav.services.ProfileringFunnet
import no.nav.utils.flywayMigrationInTest
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.TestInputTopic
import org.apache.kafka.streams.Topology
import org.apache.kafka.streams.TopologyTestDriver
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Ignore
import org.junit.Test
import java.time.ZonedDateTime
import java.util.Properties
import java.util.UUID

class KafkaApplicationTest {
    val topic = "test-topic"
    val endringPaOppfolgingsBrukerConsumer = EndringPaOppfolgingsBrukerConsumer()

    @Test
    fun `skal lagre alle nye endringer på arena-kontor i historikk tabellen`() = testApplication {
        val fnr = "12345678901"

        application {
            flywayMigrationInTest()
            val topology = configureTopology(listOf(topic to endringPaOppfolgingsBrukerConsumer::consume))
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
        val fnr = "22325678901"
        val kontor = KontorId("2228")
//        val poaoTilgangClient = mockPoaoTilgangHost(kontor.id)

        application {

            flywayMigrationInTest()
            val aktorId = "1234567890123"
            val periodeStart = ZonedDateTime.now().minusDays(2)
            val consumer = OppfolgingsPeriodeConsumer(AutomatiskKontorRutingService(
//                { poaoTilgangClient.hentTilgangsattributter(it) },
                { GTKontorFunnet(kontor) },
                { AlderFunnet(40) },
                { FnrFunnet(fnr) },
                { ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER)}
            ))
            val topology = configureTopology(listOf(topic to consumer::consume))
            val kafkaMockTopic = setupKafkaMock(topology, topic)
            kafkaMockTopic.pipeInput(
                fnr,
                oppfolgingsperiodeMessage(UUID.randomUUID().toString(), periodeStart, null, aktorId)
            )
            transaction {
                ArbeidsOppfolgingKontorEntity.Companion.findById(fnr)?.kontorId shouldBe "4154"
                KontorHistorikkEntity.Companion
                    .find { KontorhistorikkTable.fnr eq fnr }
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
            val topology = configureTopology(listOf(topic to endringPaOppfolgingsBrukerConsumer::consume))
            val kafkaMockTopic = setupKafkaMock(topology, topic)
            kafkaMockTopic.pipeInput(
                fnr, endringPaOppfolgingsBrukerMessage("1234", ZonedDateTime.parse("2025-04-10T13:01:14+02:00"))
            )
            kafkaMockTopic.pipeInput(
                fnr, endringPaOppfolgingsBrukerMessage("4321", ZonedDateTime.parse("2025-03-10T13:01:14+02:00"))
            )
            transaction {
                ArenaKontorEntity.Companion.findById(fnr)?.kontorId shouldBe "1234"
                KontorHistorikkEntity.Companion
                    .find { KontorhistorikkTable.fnr eq fnr }
                    .count() shouldBe 1
            }
        }
    }

    @Ignore
    @Test
    fun testKafkaRetry() = testApplication {
        val fnr = "12345678901"

        application {
            flywayMigrationInTest()

            val topology = configureTopology(listOf(topic to endringPaOppfolgingsBrukerConsumer::consume))
            val kafkaMockTopic = setupKafkaMock(topology, topic)
            kafkaMockTopic.pipeInput(fnr, endringPaOppfolgingsBrukerMessage("1234", ZonedDateTime.now()))
        }
    }

    @Ignore
    @Test
    fun testKafkaSkipMessage() = testApplication {
        val fnr = "12345678901"
        application {
            flywayMigrationInTest()

            val topology = configureTopology(listOf(topic to endringPaOppfolgingsBrukerConsumer::consume))
            val kafkaMockTopic = setupKafkaMock(topology, topic)
            kafkaMockTopic.pipeInput(fnr, """{"oppfolgingsenhet":"ugyldigEnhet"}""")
        }
    }

    fun endringPaOppfolgingsBrukerMessage(kontorId: String, sistEndretDato: ZonedDateTime): String {
        return """{"oppfolgingsenhet":"$kontorId", "sistEndretDato": "$sistEndretDato" }"""
    }

    fun oppfolgingsperiodeMessage(
        uuid: String,
        startDato: ZonedDateTime,
        sluttDato: ZonedDateTime?,
        aktorId: String
    ): String {
        return """{"uuid":"$uuid", "startDato":"$startDato", "sluttDato":${sluttDato?.let { "\"$it\"" } ?: "null"}, "startetBegrunnelse": "SYKEMELDT_MER_OPPFOLGING" "aktorId":"$aktorId"}"""
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
