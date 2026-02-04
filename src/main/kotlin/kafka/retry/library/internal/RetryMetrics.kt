package no.nav.kafka.retry.library.internal

import org.apache.kafka.common.MetricName
import org.apache.kafka.common.metrics.Sensor
import org.apache.kafka.common.metrics.stats.CumulativeCount
import org.apache.kafka.common.metrics.stats.Value // <-- DENNE ER KORREKT!
import org.apache.kafka.streams.processor.api.ProcessorContext
import org.slf4j.LoggerFactory

/**
 * Håndterer oppsett og oppdatering av custom metrikker for retry-prosessoren.
 * Denne implementasjonen er for Kafka Streams 4,
 * og bruker det offentlige Sensor API-et.
 * Metrikkene registreres som jmx-metrikker, men kan også hentes via Kafka Streams API-et.
 * Dersom man ønsker å bruker prometheus, må man bruke en Prometheus-jmx-exporter eller lignende.
 * Konfigurasjonen for eksportering av JMX-metrikker til Prometheus må settes opp separat, men vil se omtrent slik ut:
  ```yaml
    lowercaseOutputName: true
    lowercaseOutputLabelNames: true

  rules:
    # Mønster for å matche alle våre sensor-baserte metrikker
    - pattern: "kafka.streams<type=retry-processor-metrics, kafka-stream-task-id=(.+), sensor=(.+)><>(.+)"
      name: "kafka_streams_retry_processor_${3}" # Bruker attributtnavnet, som er mest beskrivende (f.eks. "messages-enqueued-total")
      labels:
        taskId: "$1"
        sensor: "$2" # Kan være nyttig å ha med navnet på sensoren som en label
      help: "Retry processor metric ${3}"
      type: UNTYPED
```
 */
internal class RetryMetrics(
    context: ProcessorContext<*, *>,
    private val repository: RetryableRepository,
    private val topic: String,
) {
    private val metrics = context.metrics()
    private val groupName = "retry-processor-metrics"
    private val tags = mapOf(
        "kafka-stream-task-id" to context.taskId().toString(),
        "topic" to topic,
    )

    // --- Sensorer opprettet med korrekt RecordingLevel ---
    private val messagesEnqueuedSensor: Sensor = metrics.addSensor("messages-enqueued", Sensor.RecordingLevel.INFO)
    private val retriesAttemptedSensor: Sensor = metrics.addSensor("retries-attempted", Sensor.RecordingLevel.INFO)
    private val retriesSucceededSensor: Sensor = metrics.addSensor("retries-succeeded", Sensor.RecordingLevel.INFO)
    private val retriesFailedSensor: Sensor = metrics.addSensor("retries-failed", Sensor.RecordingLevel.INFO)
    private val messagesDeadLetteredSensor: Sensor = metrics.addSensor("messages-dead-lettered", Sensor.RecordingLevel.INFO)
    private val failedMessagesCurrentSensor: Sensor = metrics.addSensor("failed-messages-current", Sensor.RecordingLevel.INFO)
    private val failedMessagesOlderThan20MinutesSensor: Sensor = metrics.addSensor("failed-messages-older-than-20-minutes", Sensor.RecordingLevel.INFO)

    init {
        // --- Definer Telle-metrikker (Counters) ---
        val totalMetricName = { name: String, desc: String -> MetricName("$name-total", groupName, desc, tags) }

        messagesEnqueuedSensor.add(totalMetricName("messages-enqueued", "Total number of messages enqueued for retry"), CumulativeCount())
        retriesAttemptedSensor.add(totalMetricName("retries-attempted", "Total number of retry attempts"), CumulativeCount())
        retriesSucceededSensor.add(totalMetricName("retries-succeeded", "Total number of successful retries"), CumulativeCount())
        retriesFailedSensor.add(totalMetricName("retries-failed", "Total number of failed retries"), CumulativeCount())
        messagesDeadLetteredSensor.add(totalMetricName("messages-dead-lettered", "Total number of messages that reached max retries"), CumulativeCount())

        // --- Definer Måler-metrikk (Gauge) ---
        // Vi bruker Value(), som er en MeasurableStat som bare rapporterer den siste registrerte verdien.
        val gaugeMetricName = MetricName("failed-messages-current", groupName, "Current number of messages in the failure queue", tags)
        failedMessagesCurrentSensor.add(gaugeMetricName, Value())
        val olderThan20MinutesGaugeMetricName = MetricName("failed-messages-older-than-20-minutes", groupName, "Current number of messages in the failure queue older than 20 minutes", tags)
        failedMessagesOlderThan20MinutesSensor.add(olderThan20MinutesGaugeMetricName, Value())
    }

    private val logger = LoggerFactory.getLogger(RetryMetrics::class.java)
    fun messageEnqueued() = messagesEnqueuedSensor.record(1.0)
    fun retryAttempted() = retriesAttemptedSensor.record(1.0)
    fun retrySucceeded() = retriesSucceededSensor.record(1.0)
    fun retryFailed() = retriesFailedSensor.record(1.0)
    fun messageDeadLettered() = messagesDeadLetteredSensor.record(1.0)

    fun updateCurrentFailedMessagesGauge() {
        try {
            val count = repository.countTotalFailedMessages().toDouble()
            failedMessagesCurrentSensor.record(count)
            val olderThan20Minutes = repository.countFailedMessagesOlderThan20Minutes().toDouble()
            failedMessagesOlderThan20MinutesSensor.record(olderThan20Minutes)
        } catch (e: Exception) {
            logger.error("Could not fetch failed message count for metrics: ${e.message}")
        }
    }
}