package kafka.retry.library.internal

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kafka.retry.TestLockProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import no.nav.db.flywayMigrate
import no.nav.db.table.FailedMessagesTable
import no.nav.db.table.FailedMessagesTable.messageKeyText
import no.nav.kafka.config.StringStringSinkConfig
import no.nav.kafka.config.processorName
import no.nav.kafka.config.streamsErrorHandlerConfig
import no.nav.kafka.processor.Commit
import no.nav.kafka.processor.Forward
import no.nav.kafka.processor.ProcessRecord
import no.nav.kafka.processor.Retry
import no.nav.kafka.retry.library.RetryConfig
import no.nav.kafka.retry.library.internal.FailedMessageRepository
import no.nav.kafka.retry.library.internal.RetryableProcessor
import no.nav.utils.TestDb
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.KeyValue
import org.apache.kafka.streams.StreamsBuilder
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.TestInputTopic
import org.apache.kafka.streams.TestOutputTopic
import org.apache.kafka.streams.Topology
import org.apache.kafka.streams.TopologyTestDriver
import org.apache.kafka.streams.kstream.Consumed
import org.apache.kafka.streams.kstream.Named
import org.apache.kafka.streams.processor.api.ProcessorSupplier
import org.apache.kafka.streams.processor.api.Record
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `should retry message when processing fails`() = runTest {
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

        val (testDriver, testInputTopics) = setupKafkaTestDriver(topic, { record ->
            val failed = failFirstThenOk()
            if (failed == Res.Fail) {
                Retry("Dette gikk galt")
            } else {
                Forward(
                    Record("lol", "lol", Instant.now().toEpochMilli()),
                    null)
            }
        })

        testInputTopics.first().pipeInput("key1", "value1")
        testInputTopics.first().pipeInput("key1", "value1")

        withClue("Shoud have enqueued message in failed message repository after first failure") {
            failedMessageRepository.hasFailedMessages("key1") shouldBe true
            countFailedMessagesOnKey("key1") shouldBe 2
        }

        testDriver.advanceWallClockTime(Duration.of(1, ChronoUnit.MINUTES))
        runCurrent()

        withClue("Should not have any failed message in failed message repository after it has been successfully processed") {
            failedMessageRepository.hasFailedMessages("key1") shouldBe false
        }
    }

    @Test
    fun `should enqueue message when processing failed for previous message on same key`() = runTest {
        val topic = "test-topic"
        val failedMessageRepository = FailedMessageRepository(topic)

        val (testDriver, testInputTopics) =  setupKafkaTestDriver(topic, { _ -> Retry("Dette gikk galt") })

        testInputTopics.first().pipeInput("key2", "value2")
        testInputTopics.first().pipeInput("key2", "value2")

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
    fun `should still have message in queue if reprocessing throws`() = runTest {
        val topic = "test-topic"
        val failedMessageRepository = FailedMessageRepository(topic)

        val (testDriver, testInputTopic) =  setupKafkaTestDriver(topic, { _ -> throw Error("Test") })

        testInputTopic.first().pipeInput("key3", "value2")

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

    /*
    @Test
    fun `Meldinger som er Forward(ed) skal sendes ut på topic og sende ut melding på sink`() {
        val inputTopic = "test-topic"
        val inputTopic2 = "test-topic-2"
        val outputTopic = "test-output-topic"
        val sinkName = "sinkName"

        val sinkConfig = StringStringSinkConfig(
            sinkName,
            outputTopic,
        )
        val topology = configureTopology()

        val (_, testInputTopics, testOutputtopic) = setupKafkaMock(topology,listOf(inputTopic, inputTopic2), outputTopic)

        testInputTopics.first().pipeInput("key3", "value2")
        testInputTopics.last().pipeInput("key3", "value2")

        testOutputtopic!!.queueSize shouldBe 2
        val record = testOutputtopic.readRecord()
        record.key shouldBe "new key"
        record.value shouldBe "new value"
        testOutputtopic.queueSize shouldBe 1
    }*/

    fun TestScope.setupKafkaTestDriver(
        topic: String,
        processRecord: ProcessRecord<String, String, String, String>,
        sinkConfigs: StringStringSinkConfig? = null,
    ) = setupKafkaTestDriver(
        topic, processRecord, sinkConfigs, this.backgroundScope
    )

    fun setupKafkaTestDriver(
        topic: String,
        processRecord: ProcessRecord<String, String, String, String>,
        sinkConfigs: StringStringSinkConfig? = null,
        punctuationCoroutineScope: CoroutineScope,
    ): Triple<TopologyTestDriver, List<TestInputTopic<String, String>>, TestOutputTopic<String, String>?> {

        val builder = StreamsBuilder()
        val testRepository = FailedMessageRepository(topic)
        val testSupplier = ProcessorSupplier {
            RetryableProcessor(
                config = RetryConfig(),
                keyInSerde = Serdes.String(),
                valueInSerde = Serdes.String(),
                topic = topic,
                repository = testRepository,
                businessLogic = processRecord,
                lockProvider = TestLockProvider,
                punctuationCoroutineScope = punctuationCoroutineScope,
            )
        }

        builder.stream(topic, Consumed.with(Serdes.String(), Serdes.String()))
            .process(testSupplier, Named.`as`(processorName(topic)))
            .foreach { key, value ->
                println(key)
                println(value)
            }
//            .process(testSupplier, Named.`as`(processorName(topic)))
//            .map({ key, value -> KeyValue(key, value) }, Named.`as`("lol"))

        val topology = builder.build()

        return setupKafkaMock(topology, listOf(topic), sinkConfigs?.outputTopicName)
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

fun setupKafkaMock(topology: Topology, inputTopics: List<String>, outputTopic: String? = null): Triple<TopologyTestDriver, List<TestInputTopic<String, String>>, TestOutputTopic<String, String>?> {
    val props = Properties()
    props.setProperty(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9091")
    props.streamsErrorHandlerConfig()
    val driver = TopologyTestDriver(topology, props)
    val inputTopics = inputTopics.map { inputTopic ->
        driver.createInputTopic(inputTopic, Serdes.String().serializer(), Serdes.String().serializer())
    }
    if (outputTopic != null) {
        val outputTopic = driver.createOutputTopic(outputTopic, Serdes.String().deserializer(), Serdes.String().deserializer())
        return Triple(driver ,inputTopics, outputTopic)
    }
    return Triple(driver ,inputTopics, null)
}