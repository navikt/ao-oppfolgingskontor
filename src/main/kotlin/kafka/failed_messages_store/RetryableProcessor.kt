package no.nav.kafka.failed_messages_store

import org.apache.kafka.common.serialization.Deserializer
import org.apache.kafka.common.serialization.Serializer
import org.apache.kafka.streams.processor.PunctuationType
import org.apache.kafka.streams.processor.api.*

internal class RetryableProcessor<K, V>(
    private val config: RetryConfig,
    private val keySerializer: Serializer<K>,
    private val valueSerializer: Serializer<V>,
    private val valueDeserializer: Deserializer<V>,
    private val topic: String, // Nødvendig for deserializer
    private val businessLogic: (Record<K, V>) -> Unit // <-- Brukerens logikk!
) : Processor<K, V, Void, Void> {

    private lateinit var context: ProcessorContext<Void, Void>
    private lateinit var store: PostgresRetryStore

    override fun init(context: ProcessorContext<Void, Void>) {
        this.context = context
        this.store = context.getStateStore(config.stateStoreName)

        context.schedule(config.retryInterval, PunctuationType.WALL_CLOCK_TIME, this::runReprocessing)
    }

    override fun process(record: Record<K, V>) {
        val keyString = record.key().toString() // Antar at key kan konverteres til en meningsfull string

        if (store.hasFailedMessages(keyString)) {
            enqueue(record, "Queued behind a previously failed message.")
            return
        }

        try {
            businessLogic(record)
        } catch (e: Exception) {
            enqueue(record, "Initial processing failed: ${e.message}")
        }
    }

    private fun runReprocessing(timestamp: Long) {
        val messagesToRetry = store.getBatchToRetry(config.retryBatchSize)

        for (msg in messagesToRetry) {
            if (msg.retryCount >= config.maxRetries) {
                // Send til DLQ eller logg og slett
                println("ERROR: Message ${msg.id} has exceeded max retries. Deleting.")
                store.delete(msg.id)
                continue
            }

            try {
                // Rekonstruer record for å sende til brukerens logikk
                val key = null // Vi har bare key som string, businessLogic må kanskje tåle null key ved retry
                val value = valueDeserializer.deserialize(topic, msg.messageValue)
                val reconstructedRecord = Record(key as K, value, msg.queueTimestamp.toInstant().toEpochMilli())

                businessLogic(reconstructedRecord)

                // Vellykket!
                store.delete(msg.id)
                println("Successfully reprocessed message ${msg.id}")

            } catch (e: Exception) {
                store.updateAfterFailedAttempt(msg.id, "Reprocessing failed: ${e.message}")
                println("WARN: Reprocessing message ${msg.id} failed again.")
            }
        }
    }

    private fun enqueue(record: Record<K, V>, reason: String) {
        val keyString = record.key().toString()
        val valueBytes = valueSerializer.serialize(topic, record.value())
        store.enqueue(keyString, valueBytes, reason)
    }

    override fun close() {
        valueDeserializer.close()
        valueSerializer.close()
        keySerializer.close()
    }
}