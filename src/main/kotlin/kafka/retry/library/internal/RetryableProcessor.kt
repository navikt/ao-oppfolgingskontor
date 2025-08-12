package no.nav.kafka.retry.library.internal

import kafka.retry.library.internal.TopicLevelLock
import kotlinx.coroutines.CoroutineScope
import no.nav.kafka.retry.library.AvroJsonConverter
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor
import net.javacrumbs.shedlock.core.LockConfiguration
import net.javacrumbs.shedlock.core.LockProvider
import no.nav.kafka.processor.Commit
import no.nav.kafka.processor.Forward
import no.nav.kafka.processor.RecordProcessingResult
import no.nav.kafka.processor.Retry
import no.nav.kafka.processor.Skip
import no.nav.kafka.retry.library.MaxRetries
import no.nav.kafka.retry.library.RetryConfig
import org.apache.avro.specific.SpecificRecord
import org.apache.kafka.common.serialization.Serde
import org.apache.kafka.streams.processor.PunctuationType
import org.apache.kafka.streams.processor.api.Processor
import org.apache.kafka.streams.processor.api.ProcessorContext
import org.apache.kafka.streams.processor.api.Record
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

/**
 * Den sentrale prosessoren i feilhåndteringsbiblioteket.
 *
 * Denne klassen er hjertet i operasjonen og utfører følgende oppgaver:
 * 1.  Sjekker om det finnes tidligere feilede meldinger for en gitt nøkkel.
 * 2.  Legger nye meldinger i kø hvis de er blokkert eller hvis de feiler under prosessering.
 * 3.  Bruker en periodisk "Punctuation" for å forsøke å reprosessere meldinger fra feilkøen.
 * 4.  Håndterer logikk for maksimalt antall forsøk ("dead-lettering").
 * 5.  Oppdaterer alle relevante metrikker via RetryMetrics-klassen.
 */
val lockAtMostFor = Duration.ofMinutes(10L)
val lockAtLeastFor = Duration.ofSeconds(5)

@PublishedApi
internal class RetryableProcessor<KIn, VIn, KOut, VOut>(
    private val config: RetryConfig,
    private val keyInSerde: Serde<KIn>,
    private val valueInSerde: Serde<VIn>,
    private val topic: String, // Nødvendig for SerDes
    private val repository: FailedMessageRepository, // Nødvendig for metrikk-initialiserin
    /* businessLogig er selve forretningslogikken fra brukeren. Kan returnere Record<KOut,VOut> eller Unit.     */
    private val businessLogic: (Record<KIn, VIn>) -> RecordProcessingResult<KOut, VOut>,
    private val lockProvider: LockProvider,
    private val punctuationCoroutineScope: CoroutineScope,
) : Processor<KIn, VIn, KOut, VOut> {
    private val keyInSerializer = keyInSerde.serializer()
    private val valueInSerializer = valueInSerde.serializer()
    private val keyInDeserializer = keyInSerde.deserializer()
    private val valueInDeserializer = valueInSerde.deserializer()

    private lateinit var context: ProcessorContext<KOut, VOut>
    private lateinit var store: PostgresRetryStore
    private lateinit var metrics: RetryMetrics
    private val lockingTaskExecutor = DefaultLockingTaskExecutor(lockProvider)
    private val logger = LoggerFactory.getLogger(RetryableProcessor::class.java)

    override fun init(context: ProcessorContext<KOut, VOut>) {
        this.context = context
        this.store = PostgresRetryStoreImpl(topic, repository)
        this.metrics = RetryMetrics(context, repository, topic)
        context.schedule(config.retryInterval, PunctuationType.WALL_CLOCK_TIME, this::runReprocessingWithLock)
    }

    override fun process(record: Record<KIn, VIn>) {
        // Vi krever en ikke-null nøkkel for å kunne garantere rekkefølge
        val key = record.key()
            ?: throw IllegalArgumentException("RetryableProcessor requires a non-null key. Cannot process message with null key.")

        val keyString = key.toString()
        val (topic, partition, offset) = context.recordMetadata().map { Triple(it.topic(), it.partition(), it.offset()) }.get()
        context.recordMetadata().map { logger.debug("Processing record with key $keyString from Kafka topic: $topic, partition: $partition, offset: $offset") }

        if (store.hasFailedMessages(keyString)) {
            enqueue(record, "Queued behind a previously failed message.")
            return
        }

        try {
            transaction {
                val result = businessLogic(record)
                when (result) {
                    is Commit, is Skip -> {
                        saveOffset(topic, partition, offset)
                    }
                    is Forward -> context.forward(result.forwardedRecord, result.topic)
                    is Retry -> enqueue(record, result.reason)
                }
            }
        } catch (e: Throwable) {
            val reason = "Initial processing failed: ${e.javaClass.simpleName} - ${e.message}"
            enqueue(record, reason)
        }
    }

    private fun runWithInterPodLevelLock(block: Runnable) {
        logger.info("Attempting to acquire inter-pod lock for topic: $topic")
        lockingTaskExecutor.executeWithLock(
            block,
            LockConfiguration(Instant.now(), "${topic}-lock", lockAtMostFor, lockAtLeastFor)
        )
    }

    private fun runReprocessingWithLock(timestamp: Long) {
        logger.info("Ready to reprocess failed messages for topic: $topic at timestamp: $timestamp")
        runWithInterPodLevelLock {
            logger.info("Starting to reprocess failed messages for topic: $topic at timestamp: $timestamp")
//            punctuationCoroutineScope.launch {
                try {
//                    withTimeout(10_000) {
                        runReprocessingOnOneBatch(timestamp)
//                    }
//                } catch (e: TimeoutCancellationException) {
//                    logger.warn("Reprocessing failed messages timed out after 10 seconds")
                } catch (e: Throwable) {
                    logger.error("Unexpected error when processing failed messages: ${e.message}", e)
                }
//            }
        }
    }

    private fun hasReachedMaxRetries(msg: FailedMessage): Boolean {
        return when (config.maxRetries) {
            is MaxRetries.Finite -> msg.retryCount >= config.maxRetries.maxRetries
            MaxRetries.Infinite -> false // Ingen begrensning på antall forsøk
        }
    }

    private fun reprocessSingleMessage(message: FailedMessage): ReprocessingResult<KIn, VIn, KOut, VOut> {
        metrics.retryAttempted()
        if (hasReachedMaxRetries(message)) return MaxRetryReached(message)
        try {
            return transaction {
                val reconstructionResult = message.toRecordReconstructedRecord()
                when (reconstructionResult) {
                    is ReconstructedRecord -> {
                        val processingResult = businessLogic(reconstructionResult.record)
                        when (processingResult) {
                            is Retry -> RetryableFail(message, Exception(processingResult.reason))
                            else -> Success(message, processingResult)
                        }
                    }

                    is UnrecoverableDeserialization -> UnrecoverableFail(message, reconstructionResult.reason)
                }
            }
        } catch (e: Throwable) {
            return RetryableFail(message, e)
        }
    }

    private fun handleReprocessingResult(result: ReprocessingResult<KIn, VIn, KOut, VOut>) {
        when (result) {
            is MaxRetryReached -> {
                metrics.messageDeadLettered()
                logger.error("Message ${result.msg.id} for key '${result.msg.messageKeyText}' has exceeded max retries. Deleting from queue.")
                store.delete(result.msg.id)
            }
            is RetryableFail -> {
                val reason = "Reprocessing failed: ${result.error.javaClass.simpleName} - ${result.error.message}"
                store.updateAfterFailedAttempt(result.msg.id, reason)
                metrics.retryFailed()
                logger.warn("Reprocessing messageId:${result.msg.id}, key:'${result.msg.messageKeyText}', topic:${topic} failed again. Reason: $reason", result.error)
            }
            is UnrecoverableFail -> {
                metrics.messageDeadLettered()
                store.delete(result.msg.id)
                logger.error("Message $result.msg.id could not be deserialized (${result.reason}). Moving to dead-letter.")
            }
            is Success -> {
                store.delete(result.msg.id)
                metrics.retrySucceeded()
                if (result.processingResult is Forward<KOut, VOut>) {
                    logger.info("Forwarding record ${result.msg.id}.")
                    context.forward(result.processingResult.forwardedRecord, result.processingResult.topic)
                }
                logger.info("Successfully reprocessed message ${result.msg.id} for key '${result.msg.messageKeyText}'.")
            }
        }
    }

    private fun FailedMessage.toRecordReconstructedRecord(): MessageReconstructionResult<KIn, VIn> {
        val keyBytes = this.messageKeyBytes ?: run {
            return UnrecoverableDeserialization(this.id, "messageKeyBytes is null in database")
        }
        val key: KIn = keyInDeserializer.deserialize(topic, keyBytes) ?: run {
            return UnrecoverableDeserialization(this.id, "Key was null after deserialization")
        }
        val value: VIn = valueInDeserializer.deserialize(topic, this.messageValue) ?: run {
            return UnrecoverableDeserialization(this.id, "Value was null after deserialization")
        }
        val record = Record(key, value, this.queueTimestamp.toInstant().toEpochMilli())
        return ReconstructedRecord(record)
    }

    private fun runReprocessingOnOneBatch(timestamp: Long) {
        logger.debug("runReprocessingOnOneBatch called for topic: $topic at timestamp: $timestamp")
        logger.debug("Update current failed messages gauge for topic: $topic")
        metrics.updateCurrentFailedMessagesGauge()
        // Prøv å skaffe låsen for MITT topic. Dette sikrer at kun én tråd om gangen kan prosessere meldinger for dette topicet.
        logger.debug("Prøver å skaffe lås for topic: $topic")
        if (!TopicLevelLock.tryAcquire(this.topic)) {
            // En annen tråd jobber allerede med dette topicet. Avslutt.
            logger.debug("En annen tråd jobber allerede med dette topicet. Avslutter prosessering")
            return
        }
        try {
            logger.debug("Fikk lokal lås for topic: $topic. Starter reprosessering av feilede meldinger.")
            logger.debug("Henter batch med feilede meldinger for reprosessering")
            store.getBatchToRetry(config.retryBatchSize)
                .also { logger.debug("hentet ${it.size} meldinger for topic: $topic") }
                .proccessInOrderOnKey {
                   reprocessSingleMessage(it).also(::handleReprocessingResult)
                }


        } finally {
            // Frigi låsen for MITT topic, slik at en annen tråd kan ta over neste gang.
            TopicLevelLock.release(this.topic)
        }
    }

    private fun saveOffset(topic: String, partition: Int, offset: Long) {

    }

    private fun enqueue(record: Record<KIn, VIn>, reason: String) {
        val key = record.key()!! // Vi har allerede sjekket for null i process()
        val keyString = key.toString()
        val keyBytes = keyInSerializer.serialize(topic, key)
        val valueBytes = valueInSerializer.serialize(topic, record.value())

        val recordValue = record.value()
        val pk = when (recordValue) {
            is SpecificRecord -> {
                val humanReadableValue = AvroJsonConverter.convertAvroToJson(recordValue)
                store.enqueue(keyString, keyBytes, valueBytes, reason, humanReadableValue)
            }

            else -> {
                store.enqueue(keyString, keyBytes, valueBytes, reason)
            }
        }

        metrics.messageEnqueued()
        logger.info("Message messageId: '$pk' was enqueued for retry. Reason: $reason")
    }

    override fun close() {
        keyInSerializer.close()
        valueInSerializer.close()
        keyInDeserializer.close()
        valueInDeserializer.close()
    }
}

fun <KIn, VIn, KOut, VOut> List<FailedMessage>.proccessInOrderOnKey(block: (message: FailedMessage) -> ReprocessingResult<KIn, VIn, KOut, VOut>): List<ReprocessingResult<KIn, VIn, KOut, VOut>> {
    return this.groupBy { it.messageKeyText }
        .flatMap { (key, messagesOnKeyInOrder) ->
            // Process messages for each key in order
            messagesOnKeyInOrder
                // Stop processing if previous message is going to be retried
                .fold(emptyList<ReprocessingResult<KIn, VIn, KOut, VOut>>()) { accResults, nextMessage ->
                    if (accResults.any { it is RetryableFail }) accResults
                    else accResults + listOf(block(nextMessage))
                }
        }
}

sealed class ReprocessingResult<KIn, VIn, KOut, VOut>(val msg: FailedMessage)
class MaxRetryReached<KIn, VIn, KOut, VOut>(msg: FailedMessage): ReprocessingResult<KIn, VIn, KOut, VOut>(msg)
class RetryableFail<KIn, VIn, KOut, VOut>(msg: FailedMessage, val error: Throwable): ReprocessingResult<KIn, VIn, KOut, VOut>(msg)
class UnrecoverableFail<KIn, VIn, KOut, VOut>(msg: FailedMessage, val reason: String): ReprocessingResult<KIn, VIn, KOut, VOut>(msg)
class Success<KIn, VIn, KOut, VOut>(msg: FailedMessage, val processingResult: RecordProcessingResult<KOut, VOut>): ReprocessingResult<KIn, VIn, KOut, VOut>(msg)

sealed class MessageReconstructionResult<KIn, VIn>()
class ReconstructedRecord<KIn, VIn>(val record: Record<KIn, VIn>) : MessageReconstructionResult<KIn, VIn>()
class UnrecoverableDeserialization<KIn, VIn>(val messageId: Long, val reason: String) : MessageReconstructionResult<KIn, VIn>()
