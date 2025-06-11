package no.nav.kafka.retry.library.internal

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
internal class RetryableProcessor<K, V>(
    private val config: RetryConfig,
    private val keySerializer: Serializer<K>,
    private val valueSerializer: Serializer<V>,
    private val valueDeserializer: Deserializer<V>,
    private val topic: String, // Nødvendig for SerDes
    private val repository: FailedMessageRepository, // Nødvendig for metrikk-initialisering
    private val businessLogic: (Record<K, V>) -> Unit // Selve forretningslogikken fra brukeren
) : Processor<K, V, Void, Void> {
    val logger = LoggerFactory.getLogger(RetryableProcessor::class.java)

    private lateinit var context: ProcessorContext<Void, Void>
    private lateinit var store: PostgresRetryStore
    private lateinit var metrics: RetryMetrics

    override fun init(context: ProcessorContext<Void, Void>) {
        this.context = context
        this.store = context.getStateStore(config.stateStoreName)
        this.metrics = RetryMetrics(context, repository)

        // Schedule punctuation til å kjøre periodisk for å håndtere reprosessering.
        context.schedule(config.retryInterval, PunctuationType.WALL_CLOCK_TIME, this::runReprocessing)
    }

    override fun process(record: Record<K, V>) {
        val keyString = record.key()?.toString() ?: "null-key"

        // 1. Sjekk om det allerede finnes feilede meldinger for denne nøkkelen
        if (store.hasFailedMessages(keyString)) {
            enqueue(record, "Queued behind a previously failed message.")
            return
        }

        // 2. Forsøk å prosessere meldingen med brukerens logikk
        try {
            businessLogic(record)
        } catch (e: Exception) {
            // 3. Hvis prosessering feiler, legg den i feilkøen
            val reason = "Initial processing failed: ${e.javaClass.simpleName} - ${e.message}"
            enqueue(record, reason)
        }
    }

    /**
     * Denne metoden kalles periodisk av Kafka Streams (via Punctuation).
     * Den henter en batch med meldinger fra databasen og prøver å prosessere dem på nytt.
     */
    private fun runReprocessing(timestamp: Long) {
        // VIKTIG: Oppdater gaugen for nåværende antall feilede meldinger manuelt.
        metrics.updateCurrentFailedMessagesGauge()

        val messagesToRetry = store.getBatchToRetry(config.retryBatchSize)

        for (msg in messagesToRetry) {
            metrics.retryAttempted()

            if (config.maxRetries != -1 && msg.retryCount >= config.maxRetries) {
                // Meldingen har feilet for mange ganger. Betraktes som "dead-letter".
                metrics.messageDeadLettered()
                logger.error("Message ${msg.id} for key '${msg.messageKey}' has exceeded max retries (${config.maxRetries}). Deleting from queue.")
                store.delete(msg.id)
                continue // Gå til neste melding
            }

            try {
                // Rekonstruer meldingen og prøv brukerens logikk på nytt.
                val value = valueDeserializer.deserialize(topic, msg.messageValue)
                // Nøkkelen er `null` her siden vi ikke serialiserte den. Brukerens logikk må kunne håndtere dette ved retry.
                val reconstructedRecord = Record(null as K, value, msg.queueTimestamp.toInstant().toEpochMilli())

                businessLogic(reconstructedRecord)

                // Vellykket! Slett fra databasen og oppdater metrikk.
                store.delete(msg.id)
                metrics.retrySucceeded()
                logger.info("Successfully reprocessed message ${msg.id} for key '${msg.messageKey}'.")

            } catch (e: Exception) {
                // Feilet igjen. Oppdater databasen og metrikken.
                val reason = "Reprocessing failed: ${e.javaClass.simpleName} - ${e.message}"
                store.updateAfterFailedAttempt(msg.id, reason)
                metrics.retryFailed()
                logger.warn("Reprocessing message ${msg.id} for key '${msg.messageKey}' failed again. Reason: $reason")
            }
        }
    }

    /**
     * Hjelpemetode for å serialisere og lagre en feilet melding i databasen.
     */
    private fun enqueue(record: Record<K, V>, reason: String) {
        val keyString = record.key()?.toString() ?: "null-key"
        val valueBytes = valueSerializer.serialize(topic, record.value())
        store.enqueue(keyString, valueBytes, reason)
        metrics.messageEnqueued()
        logger.info("Message for key '$keyString' was enqueued for retry. Reason: $reason")
    }

    override fun close() {
        // Rydd opp i ressursene som denne prosessor-instansen eier.
        keySerializer.close()
        valueSerializer.close()
        valueDeserializer.close()
    }
}