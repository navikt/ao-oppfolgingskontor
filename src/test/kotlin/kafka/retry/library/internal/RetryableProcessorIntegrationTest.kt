package kafka.retry.library.internal

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import no.nav.db.flywayMigrate
import no.nav.kafka.config.StringTopicConsumer
import no.nav.kafka.config.configureTopology
import no.nav.kafka.config.streamsErrorHandlerConfig
import no.nav.kafka.processor.Commit
import no.nav.kafka.processor.Retry
import no.nav.kafka.retry.library.internal.FailedMessageRepository
import no.nav.utils.TestDb
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.TestInputTopic
import org.apache.kafka.streams.Topology
import org.apache.kafka.streams.TopologyTestDriver
import org.apache.kafka.streams.test.TestRecord
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.time.temporal.ChronoUnit
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

        var hasFailed = false
        fun failFirstThenOk(): Boolean {
            if (!hasFailed) {
                hasFailed = true
                return false // Simulate failure on the first call
            } else {
                return true
            }
        }
        val topology = configureTopology(
            listOf(
                StringTopicConsumer(
                    topic = "test-topic",
                    processRecord = { record, metadata ->
                        val failed = failFirstThenOk()
                        if (failed) {
                            Retry("Dette gikk galt")
                        } else {
                            Commit
                        }
                    }
                )
            )
        )
        val (testDriver, testInputTopic) = setupKafkaMock(topology, topic)

        testInputTopic.pipeInput("key1", "value1")
        testInputTopic.pipeInput(TestRecord("key1", "value1"))

        withClue("Shoud have enqueued message in failed message repository") {
            failedMessageRepository.hasFailedMessages("key1") shouldBe true
        }

        testDriver.advanceWallClockTime(Duration.of(1, ChronoUnit.MINUTES))

        withClue("Shoud not have any failed message in failed message repository") {
            failedMessageRepository.hasFailedMessages("key1") shouldBe false
        }
    }

}

private fun setupKafkaMock(topology: Topology, topic: String): Pair<TopologyTestDriver, TestInputTopic<String, String>> {
    val props = Properties()
    props.setProperty(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9091")
    props.streamsErrorHandlerConfig()
    val driver = TopologyTestDriver(topology, props)
    val inputTopic = driver.createInputTopic(topic, Serdes.String().serializer(), Serdes.String().serializer())
    return driver to inputTopic
}