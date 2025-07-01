package no.nav.kafka.retry.library.internal

import no.nav.db.table.FailedMessagesTable
import no.nav.kafka.processor.Commit
import no.nav.kafka.processor.Forward
import no.nav.kafka.processor.RecordProcessingResult
import no.nav.kafka.processor.Retry
import no.nav.kafka.processor.Skip
import no.nav.kafka.retry.library.RetryConfig
import org.apache.kafka.common.serialization.Deserializer
import org.apache.kafka.common.serialization.Serializer
import org.apache.kafka.streams.processor.PunctuationType
import org.apache.kafka.streams.processor.api.Processor
import org.apache.kafka.streams.processor.api.ProcessorContext
import org.apache.kafka.streams.processor.api.Record
import org.slf4j.LoggerFactory

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
@PublishedApi
internal class RetryableProcessor<KIn, VIn, KOut, VOut>(
    private val config: RetryConfig,
    private val keyInSerializer: Serializer<KIn>,
    private val valueInSerializer: Serializer<VIn>,
    private val keyInDeserializer: Deserializer<KIn>,
    private val valueInDeserializer: Deserializer<VIn>,
    private val topic: String, // Nødvendig for SerDes
    private val repository: FailedMessageRepository, // Nødvendig for metrikk-initialiserin
    /* businessLogig er selve forretningslogikken fra brukeren. Kan returnere Record<KOut,VOut> eller Unit.     */
    private val businessLogic: (Record<KIn, VIn>) -> RecordProcessingResult<KOut, VOut>
//    private val businessLogic: (Record<KIn, VIn>) -> Record<KOut, VOut>?
) : Processor<KIn, VIn, KOut, VOut> {

    private lateinit var context: ProcessorContext<KOut, VOut>
    private lateinit var store: PostgresRetryStore
    private lateinit var metrics: RetryMetrics

    private val logger = LoggerFactory.getLogger(RetryableProcessor::class.java)

    override fun init(context: ProcessorContext<KOut, VOut>) {
        this.context = context
        this.store = PostgresRetryStoreImpl(topic, repository)
        this.metrics = RetryMetrics(context, repository)
        context.schedule(config.retryInterval, PunctuationType.WALL_CLOCK_TIME, this::runReprocessing)
    }

    override fun process(record: Record<KIn, VIn>) {
        // Vi krever en ikke-null nøkkel for å kunne garantere rekkefølge
        val key = record.key()
            ?: throw IllegalArgumentException("RetryableProcessor requires a non-null key. Cannot process message with null key.")

        val keyString = key.toString()
        context.recordMetadata().map { logger.debug("Processing record with key $keyString from Kafka topic: ${it.topic()}, partition: ${it.partition()}, offset: ${it.offset()}") }

        if (store.hasFailedMessages(keyString)) {
            enqueue(record, "Queued behind a previously failed message.")
            return
        }

        try {
            val result = businessLogic(record)
            when (result) {
                Commit, Skip -> {}
                is Forward -> context.forward(result.forwardedRecord)
                is Retry -> {
                    val reason = "TODO: Bruk retry feilmelding her"
                    enqueue(record, reason)
                }
            }
        } catch (e: Exception) {
            val reason = "Initial processing failed: ${e.javaClass.simpleName} - ${e.message}"
            enqueue(record, reason)
        }
    }

    private fun runReprocessing(timestamp: Long) {
        metrics.updateCurrentFailedMessagesGauge()
        val messagesToRetry = store.getBatchToRetry(config.retryBatchSize)

        for (msg in messagesToRetry) {
            metrics.retryAttempted()

            if (msg.retryCount >= config.maxRetries) {
                metrics.messageDeadLettered()
                logger.error("Message ${msg.id} for key '${msg.messageKeyText}' has exceeded max retries. Deleting from queue.")
                store.delete(msg.id)
                continue
            }

            try {
                // Håndter feil under deserialisering
                val keyBytes = msg.messageKeyBytes ?: run {
                    handleUnrecoverableDeserialization(msg.id, "messageKeyBytes is null in database")
                    return@runReprocessing
                }
                val key = keyInDeserializer.deserialize(topic, keyBytes)
                val value = valueInDeserializer.deserialize(topic, msg.messageValue)

                if (key == null || value == null) {
                    handleUnrecoverableDeserialization(msg.id, "key or value was null after deserialization")
                    continue
                }

                val reconstructedRecord = Record(key, value, msg.queueTimestamp.toInstant().toEpochMilli())

                // Kjør den samme logikken på nytt
                val result = businessLogic(reconstructedRecord)

                // Håndter suksess
                store.delete(msg.id)
                metrics.retrySucceeded()
                result.let {
                    if (it is Forward) {
                        context.forward(it.forwardedRecord)
                    }
                }
                logger.info("Successfully reprocessed message ${msg.id} for key '${msg.messageKeyText}'.")

            } catch (e: Exception) {
                // Håndter feil under retry
                val reason = "Reprocessing failed: ${e.javaClass.simpleName} - ${e.message}"
                store.updateAfterFailedAttempt(msg.id, reason)
                metrics.retryFailed()
                logger.warn("Reprocessing message ${msg.id} for key '${msg.messageKeyText}' failed again. Reason: $reason")
            }
        }
    }

    private fun enqueue(record: Record<KIn, VIn>, reason: String) {
        val key = record.key()!! // Vi har allerede sjekket for null i process()
        val keyString = key.toString()
        val keyBytes = keyInSerializer.serialize(topic, key)
        val valueBytes = valueInSerializer.serialize(topic, record.value())

        store.enqueue(keyString, keyBytes, valueBytes, reason)
        metrics.messageEnqueued()
        logger.info("Message for key '$keyString' was enqueued for retry. Reason: $reason")
    }

    /** Hjelpemetode for å håndtere ugjenopprettelige deserialiseringsfeil. */
    private fun handleUnrecoverableDeserialization(messageId: Long, reason: String) {
        metrics.messageDeadLettered()
        store.delete(messageId)
        logger.error("Message $messageId could not be deserialized ($reason). Moving to dead-letter.")
    }

    override fun close() {
        keyInSerializer.close()
        valueInSerializer.close()
        keyInDeserializer.close()
        valueInDeserializer.close()
    }
}