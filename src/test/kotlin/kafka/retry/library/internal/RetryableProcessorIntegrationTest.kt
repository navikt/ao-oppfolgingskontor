package kafka.retry.library.internal

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kafka.retry.TestLockProvider
import no.nav.db.flywayMigrate
import no.nav.db.table.FailedMessagesTable
import no.nav.db.table.FailedMessagesTable.messageKeyText
import no.nav.kafka.config.SinkConfig
import no.nav.kafka.config.StringStringSinkConfig
import no.nav.kafka.config.StringTopicConsumer
import no.nav.kafka.config.configureTopology
import no.nav.kafka.config.processorName
import no.nav.kafka.config.streamsErrorHandlerConfig
import no.nav.kafka.processor.Commit
import no.nav.kafka.processor.Forward
import no.nav.kafka.processor.ProcessRecord
import no.nav.kafka.processor.Retry
import no.nav.kafka.retry.library.internal.FailedMessageRepository
import no.nav.utils.TestDb
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.TestInputTopic
import org.apache.kafka.streams.TestOutputTopic
import org.apache.kafka.streams.Topology
import org.apache.kafka.streams.TopologyTestDriver
import org.apache.kafka.streams.processor.api.Record
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.Properties

enum class Res {
    Fail,
    Succ
}

class RetryableProcessorIntegrationTest {

    @BeforeEach
    fun setup() {
        // Flyway migration or any other setup can be done here if needed
        flywayMigrate(TestDb.postgres)
    }

    @Test
    fun `should retry message when processing fails`() {
        val topic = "test-topic"
        val failedMessageRepository = FailedMessageRepository(topic)

        var hasFailed = false
        fun failFirstThenOk(): Res {
            if (!hasFailed) {
                hasFailed = true
                return Res.Fail // Simulate failure on the first call
            } else {
                return Res.Succ
            }
        }

        val (testDriver, testInputTopic) = setupKafkaTestDriver(topic, { record ->
            val failed = failFirstThenOk()
            if (failed == Res.Fail) {
                Retry("Dette gikk galt")
            } else {
                Commit()
            }
        })

        testInputTopic.pipeInput("key1", "value1")
        testInputTopic.pipeInput("key1", "value1")

        withClue("Shoud have enqueued message in failed message repository after first failure") {
            failedMessageRepository.hasFailedMessages("key1") shouldBe true
            countFailedMessagesOnKey("key1") shouldBe 2
        }

        testDriver.advanceWallClockTime(Duration.of(1, ChronoUnit.MINUTES))

        withClue("Should not have any failed message in failed message repository after it has been successfully processed") {
            failedMessageRepository.hasFailedMessages("key1") shouldBe false
        }
    }

    @Test
    fun `should enqueue message when processing failed for previous message on same key`() {
        val topic = "test-topic"
        val failedMessageRepository = FailedMessageRepository(topic)

        val (testDriver, testInputTopic) =  setupKafkaTestDriver(topic, { _ -> Retry("Dette gikk galt") })

        testInputTopic.pipeInput("key2", "value2")
        testInputTopic.pipeInput("key2", "value2")

        withClue("Shoud have enqueued message in failed message repository after first failure") {
            failedMessageRepository.hasFailedMessages("key2") shouldBe true
            countFailedMessagesOnKey("key2") shouldBe 2
        }

        testDriver.advanceWallClockTime(Duration.of(1, ChronoUnit.MINUTES))

        withClue("Should still be 2 failed messages on key") {
            countFailedMessagesOnKey("key2") shouldBe 2
            failedMessageRepository.hasFailedMessages("key2") shouldBe true
        }
    }

    @Test
    fun `should still have message in queue if reprocessing throws`() {
        val topic = "test-topic"
        val failedMessageRepository = FailedMessageRepository(topic)

        val (testDriver, testInputTopic) =  setupKafkaTestDriver(topic, { _ -> throw Error("Test") })

        testInputTopic.pipeInput("key3", "value2")

        withClue("Shoud have enqueued message in failed message repository after first failure") {
            failedMessageRepository.hasFailedMessages("key3") shouldBe true
            countFailedMessagesOnKey("key3") shouldBe 1
        }

        testDriver.advanceWallClockTime(Duration.of(1, ChronoUnit.MINUTES))

        withClue("Should still be 1 failed messages on key") {
            countFailedMessagesOnKey("key3") shouldBe 1
            failedMessageRepository.hasFailedMessages("key3") shouldBe true
        }
    }

    @Test
    fun `Meldinger som er Forward skal sendes ut på topic sende ut melding på sink`() {
        val inputTopic = "test-topic"
        val outputTopic = "test-output-topic"
        val sinkName = "sinkName"
        val (_, testInputTopic, testOutputtopic) = setupKafkaTestDriver(inputTopic,
            { record ->
                    val record: Record<String, String> = Record("new key", "new value", ZonedDateTime.now().toEpochSecond())
                    Forward(record, sinkName)
                },
            StringStringSinkConfig(sinkName, outputTopic, listOf(processorName(inputTopic))),
        )

        testInputTopic.pipeInput("key3", "value2")

        testOutputtopic!!.queueSize shouldBe 1
        val record = testOutputtopic.readRecord()
        record.key shouldBe "new key"
        record.value shouldBe "new value"
        testOutputtopic.queueSize shouldBe 0
    }

    fun setupKafkaTestDriver(
        topic: String,
        processRecord: ProcessRecord<String, String, String, String>,
        sinkConfigs: SinkConfig<*, *>? = null,
    ): Triple<TopologyTestDriver, TestInputTopic<String, String>, TestOutputTopic<String, String>?> {
        val topology = configureTopology(
            listOf(StringTopicConsumer(topic, processRecord)),
            listOfNotNull(sinkConfigs),
            TestLockProvider,
        )
        return setupKafkaMock(topology, topic, sinkConfigs?.outputTopicName)
    }

    fun countFailedMessagesOnKey(key: String): Long {
        return transaction {
            FailedMessagesTable
                .selectAll()
                .where { messageKeyText eq key }
                .count()
        }
    }

}

private fun setupKafkaMock(topology: Topology, inputTopic: String, outputTopic: String? = null): Triple<TopologyTestDriver, TestInputTopic<String, String>, TestOutputTopic<String, String>?> {
    val props = Properties()
    props.setProperty(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9091")
    props.streamsErrorHandlerConfig()
    val driver = TopologyTestDriver(topology, props)
    val inputTopic = driver.createInputTopic(inputTopic, Serdes.String().serializer(), Serdes.String().serializer())
    if (outputTopic != null) {
        val outputTopic = driver.createOutputTopic(outputTopic, Serdes.String().deserializer(), Serdes.String().deserializer())
        return Triple(driver ,inputTopic, outputTopic)
    }
    return Triple(driver ,inputTopic, null)
}