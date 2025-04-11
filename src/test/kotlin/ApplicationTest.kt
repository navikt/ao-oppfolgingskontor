package no.nav

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.server.application.*
import io.ktor.server.testing.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import no.nav.db.FlywayPlugin
import no.nav.db.entity.ArenaKontorEntity
import no.nav.db.entity.KontorHistorikkEntity
import no.nav.db.table.KontorhistorikkTable
import no.nav.kafka.EndringPaOppfolgingsBrukerConsumer
import no.nav.kafka.config.configureTopology
import no.nav.kafka.config.streamsErrorHandlerConfig
import no.nav.kafka.convertToOffsetDatetime
import no.nav.kafka.processor.RecordProcessingResult
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.TestInputTopic
import org.apache.kafka.streams.Topology
import org.apache.kafka.streams.TopologyTestDriver
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Ignore
import java.util.*
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.todo

class ApplicationTest {
    val postgres: DataSource by lazy {
        EmbeddedPostgres.start().postgresDatabase
            .also {
                Database.connect(it)
            }
    }

    @Test
    fun `skal kun lagre nyere data i arena-kontor tabell og historikk tabellen`() = testApplication {
        val topic = "test-topic"
        val consumer = EndringPaOppfolgingsBrukerConsumer()
        val fnr = "12345678901"

        application {
            install(FlywayPlugin) {
                this.dataSource = postgres
            }
            val topology = configureTopology(topic, consumer::consume)
            val kafkaMockTopic = setupKafkaMock(topology, topic)
            kafkaMockTopic.pipeInput(fnr, """{"oppfolgingsenhet":"1234", "sistEndretDato": "2025-04-10T13:01:14+02:00" }""")
            kafkaMockTopic.pipeInput(fnr, """{"oppfolgingsenhet":"4321", "sistEndretDato": "2025-03-10T13:01:14+02:00" }""")
            transaction {
                ArenaKontorEntity.findById(fnr)?.kontorId shouldBe "1234"
                KontorHistorikkEntity.find {
                    KontorhistorikkTable.fnr eq fnr
                }.count() shouldBe 1
            }
        }
    }

    @Test
    fun `skal lagre alle nye endringer på arena-kontor i historikk tabellen`() = testApplication {
        val topic = "test-topic"
        val consumer = EndringPaOppfolgingsBrukerConsumer()
        val fnr = "12345678901"

        application {
            install(FlywayPlugin) {
                this.dataSource = postgres
            }
            val topology = configureTopology(topic, consumer::consume)
            val kafkaMockTopic = setupKafkaMock(topology, topic)
            kafkaMockTopic.pipeInput(fnr, """{"oppfolgingsenhet":"1234", "sistEndretDato": "2025-04-10T13:01:14+02:00" }""")
            kafkaMockTopic.pipeInput(fnr, """{"oppfolgingsenhet":"4321", "sistEndretDato": "2025-05-10T13:01:14+02:00" }""")
            transaction {
                ArenaKontorEntity.findById(fnr)?.kontorId shouldBe "4321"
                KontorHistorikkEntity.find {
                    KontorhistorikkTable.fnr eq fnr
                }.count() shouldBe 2
            }
        }
    }

    @Ignore
    @Test
    fun testKafkaRetry() = testApplication {
        val topic = "test-topic"
        val fnr = "12345678901"
        val consumer = EndringPaOppfolgingsBrukerConsumer()

        application {
            install(FlywayPlugin) {
                this.dataSource = postgres
            }

            val topology = configureTopology(topic, consumer::consume)
            val kafkaMockTopic = setupKafkaMock(topology, topic)
            kafkaMockTopic.pipeInput(fnr, """{"oppfolgingsenhet":"1234", "sistEndretDato": "2025-05-10T13:01:14+02:00" }""")
        }
    }

    @Ignore
    @Test
    fun testKafkaSkipMessage() = testApplication {
        val topic = "test-topic"
        val consumer = EndringPaOppfolgingsBrukerConsumer()
        val fnr = "12345678901"

        application {
            install(FlywayPlugin) {
                this.dataSource = postgres
            }

            val topology = configureTopology(topic, consumer::consume)
            val kafkaMockTopic = setupKafkaMock(topology, topic)
            kafkaMockTopic.pipeInput(fnr, """{"oppfolgingsenhet":"ugyldigEnhet"}""")
            todo { "assert" }

        }
    }

    @Test
    fun testDateTimeParse() {
        val dateTimeString = "2025-04-10T13:01:14+02"
        val localDateTime = dateTimeString.convertToOffsetDatetime()
        localDateTime.shouldNotBeNull()
        println(localDateTime)
    }
}




fun setupKafkaMock(topology: Topology, topic: String): TestInputTopic<String, String> {
    val props = Properties()
    props.setProperty(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9091")
    props.setProperty(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String()::class.java.name)
    props.setProperty(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String()::class.java.name)
    props.streamsErrorHandlerConfig()
    val driver = TopologyTestDriver(topology, props)
    return driver.createInputTopic(topic, Serdes.String().serializer(), Serdes.String().serializer())
}
