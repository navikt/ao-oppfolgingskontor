package no.nav

import io.ktor.server.application.*
import io.ktor.server.testing.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import no.nav.db.FlywayPlugin
import no.nav.kafka.KafkaStreamsPlugin
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.Topology
import org.apache.kafka.streams.TopologyTestDriver
import org.jetbrains.exposed.sql.Database
import java.util.*
import kotlin.test.Test

class ApplicationTest {

    @Test
    fun testRoot() = testApplication {
        val postgres = EmbeddedPostgres.start()
        Database.connect(postgres.postgresDatabase)

        application {
            install(FlywayPlugin) {
                this.dataSource = postgres.postgresDatabase
            }
            install(KafkaStreamsPlugin)

            setupKafkaMock(Topology())
        }
    }
}


fun setupKafkaMock(topology: Topology) {
    val props = Properties();
    props.setProperty(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9091");
//    props.setProperty(StreamsConfig.DEFAULT_TIMESTAMP_EXTRACTOR_CLASS_CONFIG, CustomTimestampExtractor.class.getName());
    props.setProperty(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String()::class.java.name)
    props.setProperty(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String()::class.java.name)
    val driver = TopologyTestDriver(topology, props);
    val inputTopic = driver.createInputTopic("input-topic", Serdes.String().serializer(), Serdes.String().serializer());
    inputTopic.pipeInput("key1", "value1");

}
