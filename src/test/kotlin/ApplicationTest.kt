package no.nav

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldNotBeNull
import io.ktor.server.application.*
import io.ktor.server.testing.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import no.nav.db.FlywayPlugin
import no.nav.db.entity.ArenaKontorEntity
import no.nav.kafka.EndringPaOppfolgingsBrukerConsumer
import no.nav.kafka.KafkaStreamsPlugin
import no.nav.kafka.config.configureTopology
import no.nav.kafka.config.streamsErrorHandlerConfig
import no.nav.kafka.processor.RecordProcessingResult
import no.nav.kafka.processor.UnhandledRecordProcessingException
import org.apache.kafka.common.errors.RetriableException
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.TestInputTopic
import org.apache.kafka.streams.Topology
import org.apache.kafka.streams.TopologyTestDriver
import org.apache.kafka.streams.errors.StreamsException
import org.apache.kafka.streams.processor.api.Record
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Ignore
import org.junit.jupiter.api.BeforeAll
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
    fun testRoot() = testApplication {
        val topic = "test-topic"
        val consumer = EndringPaOppfolgingsBrukerConsumer()
        val fnr = "12345678901"

        application {
            install(FlywayPlugin) {
                this.dataSource = postgres
            }

            val topology = configureTopology(topic, consumer::consume)

            val kafkaMockTopic = setupKafkaMock(topology, topic)
            kafkaMockTopic.pipeInput(fnr, """{"oppfolgingsenhet":"1234"}""")

            transaction {
                ArenaKontorEntity.findById(fnr).shouldNotBeNull()
            }
        }
    }

    @Ignore
    @Test
    fun testKafkaRetry() = testApplication {
        val topic = "test-topic"
        class FailingConsumer {
            var runs = 0
            fun consume(record: Record<String, String>): RecordProcessingResult {
                if (runs == 1) {
                    println("Message processing ok")
                }
                runs++
                throw RuntimeException("Test exception")
            }
        }

        val consumer = FailingConsumer()
        val fnr = "12345678901"

        application {
            install(FlywayPlugin) {
                this.dataSource = postgres
            }

            val topology = configureTopology(topic, consumer::consume)
            val kafkaMockTopic = setupKafkaMock(topology, topic)
            shouldThrow<StreamsException> {
                kafkaMockTopic.pipeInput(fnr, """{"oppfolgingsenhet":"ugyldigEnhet"}""")
            }
            kafkaMockTopic.pipeInput(fnr, """{"oppfolgingsenhet":"0101"}""")



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
