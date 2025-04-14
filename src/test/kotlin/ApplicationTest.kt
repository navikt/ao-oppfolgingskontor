package no.nav

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.*
import io.ktor.server.testing.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import no.nav.db.FlywayPlugin
import no.nav.db.Fnr
import no.nav.db.entity.ArenaKontorEntity
import no.nav.db.entity.KontorHistorikkEntity
import no.nav.db.table.ArenaKontorTable
import no.nav.db.table.KontorhistorikkTable
import no.nav.graphql.graphQlModule
import no.nav.graphql.schemas.KontorQueryDto
import no.nav.kafka.EndringPaOppfolgingsBrukerConsumer
import no.nav.kafka.config.configureTopology
import no.nav.kafka.config.streamsErrorHandlerConfig
import no.nav.kafka.convertToOffsetDatetime
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.TestInputTopic
import org.apache.kafka.streams.Topology
import org.apache.kafka.streams.TopologyTestDriver
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Ignore
import java.util.*
import javax.sql.DataSource
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.config.MapApplicationConfig
import kotlinx.serialization.Serializable
import no.nav.security.mock.oauth2.MockOAuth2Server
import no.nav.security.mock.oauth2.OAuth2Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

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
            kafkaMockTopic.pipeInput(
                fnr,
                """{"oppfolgingsenhet":"1234", "sistEndretDato": "2025-04-10T13:01:14+02:00" }"""
            )
            kafkaMockTopic.pipeInput(
                fnr,
                """{"oppfolgingsenhet":"4321", "sistEndretDato": "2025-03-10T13:01:14+02:00" }"""
            )
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
            kafkaMockTopic.pipeInput(
                fnr,
                """{"oppfolgingsenhet":"1234", "sistEndretDato": "2025-04-10T13:01:14+02:00" }"""
            )
            kafkaMockTopic.pipeInput(
                fnr,
                """{"oppfolgingsenhet":"4321", "sistEndretDato": "2025-05-10T13:01:14+02:00" }"""
            )
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
            kafkaMockTopic.pipeInput(
                fnr,
                """{"oppfolgingsenhet":"1234", "sistEndretDato": "2025-05-10T13:01:14+02:00" }"""
            )
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

        }
    }

    @Test
    fun `skal kunne hente kontor via graphql`() = testApplication {
        server.start()
        environment {
            this.config = doConfig()
        }

        val fnr = "12345678901"
        val kontorId = "4142"

        val token = server.issueToken().serialize()

        application {
            install(FlywayPlugin) {
                this.dataSource = postgres
            }
            configureSecurity()
            graphQlModule()
            gittBrukerMedKontorIArena(fnr, kontorId)
        }
        val client = createClient {
            install(ContentNegotiation) {
                json()
            }
        }

        val response = client.post("/graphql") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"variables": { "fnr": $fnr }, "query": " { kontorForBruker (fnrParam: \"$fnr\") { kontorId } }"}""")
        }

        response.status shouldBe HttpStatusCode.OK
        val lol = response.body<GraphqlResponse>()
        lol shouldBe GraphqlResponse(Data(KontorQueryDto(kontorId)))
        server.shutdown()
    }

    @Test
    fun testDateTimeParse() {
        val dateTimeString = "2025-04-10T13:01:14+02"
        val localDateTime = dateTimeString.convertToOffsetDatetime()
        localDateTime.shouldNotBeNull()
        println(localDateTime)
    }

    private fun gittBrukerMedKontorIArena(fnr: Fnr, kontorId: String) {
        transaction {
            ArenaKontorTable.insert {
                it[id] = fnr
                it[this.kontorId] = kontorId
            }
        }
    }

    /* Default issuer is "default" and default aud is "default" */
    val server = MockOAuth2Server()
    private fun doConfig(
        acceptedIssuer: String = "default",
        acceptedAudience: String = "default"): MapApplicationConfig {
        return MapApplicationConfig().apply {
            put("no.nav.security.jwt.issuers.size", "1")
            put("no.nav.security.jwt.issuers.0.issuer_name", acceptedIssuer)
            put("no.nav.security.jwt.issuers.0.discoveryurl", "${server.wellKnownUrl(acceptedIssuer)}")
            put("no.nav.security.jwt.issuers.0.accepted_audience", acceptedAudience)
        }
    }
}

@Serializable
data class GraphqlResponse(
    val data: Data
)

@Serializable
data class Data(
    val kontorForBruker: KontorQueryDto,
)

fun setupKafkaMock(topology: Topology, topic: String): TestInputTopic<String, String> {
    val props = Properties()
    props.setProperty(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9091")
    props.setProperty(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String()::class.java.name)
    props.setProperty(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String()::class.java.name)
    props.streamsErrorHandlerConfig()
    val driver = TopologyTestDriver(topology, props)
    return driver.createInputTopic(topic, Serdes.String().serializer(), Serdes.String().serializer())
}
