package kafka.retry.library.internal

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import no.nav.db.flywayMigrate
import no.nav.kafka.config.StringTopicConsumer
import no.nav.kafka.config.configureTopology
import no.nav.kafka.config.streamsErrorHandlerConfig
import no.nav.kafka.processor.RecordProcessingResult
import no.nav.kafka.processor.Retry
import no.nav.kafka.retry.library.internal.FailedMessageRepository
import no.nav.utils.TestDb
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.TestInputTopic
import org.apache.kafka.streams.Topology
import org.apache.kafka.streams.TopologyTestDriver
import org.junit.Before
import org.junit.Test
import java.util.Properties

class RetryableProcessorIntegrationTest {

    @Before
    fun setup() {
        // Flyway migration or any other setup can be done here if needed
        flywayMigrate(TestDb.postgres)
    }

    @Test
    fun `should enqueue message when processing fails`() {
        val topic = "test-topic"
        val failedMessageRepository = FailedMessageRepository(topic)
        val topology = configureTopology(
            listOf(
                StringTopicConsumer(
                    topic = "test-topic",
                    processRecord = { record, _ -> Retry("Dette gikk galt") }
                )
            )
        )
        val testInputTopic = setupKafkaMock(topology, topic)

        testInputTopic.pipeInput("key1", "value1")

        withClue("Shoud have enqueued message in failed message repository") {
            failedMessageRepository.hasFailedMessages("key1") shouldBe true
        }
    }

}

private fun setupKafkaMock(topology: Topology, topic: String): TestInputTopic<String, String> {
    val props = Properties()
    props.setProperty(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9091")
    props.streamsErrorHandlerConfig()
    val driver = TopologyTestDriver(topology, props)
    return driver.createInputTopic(topic, Serdes.String().serializer(), Serdes.String().serializer())
}