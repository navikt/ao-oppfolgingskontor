package no.nav.kafka.exceptionHandler

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.apache.kafka.streams.KafkaStreams
import org.apache.kafka.streams.errors.StreamsException
import org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler
import org.apache.kafka.streams.processor.TaskId
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlin.math.pow

class TaskProcessingException(
    val taskId: TaskId,
    message:String,
    cause: Throwable? = null
): RuntimeException("Error in Task [$taskId]: $message", cause)

class KafkaStreamsTaskMonitor(
    private val applicationId: String,
    private val meterRegistry: MeterRegistry
) {
    companion object {
        private val log = LoggerFactory.getLogger(KafkaStreamsTaskMonitor::class.java)
        const val METRIC_NAME = "kafka_streams_task_health_status"
        const val STATE_OK = 0
        const val STATE_LOOPING = 1
        const val STATE_ERROR = 2

        fun taskMetricKey(taskId: TaskId): String = taskId.toString()

        fun getTaskIdFromException(throwable: Throwable?): TaskId? {
            var current: Throwable? = throwable
            while (current != null) {
                if (current is TaskProcessingException) {
                    return current.taskId
                }
                if (current.cause is TaskProcessingException) {
                    return (current.cause as TaskProcessingException).taskId
                }
                current = current.cause
            }
            return null
        }
    }
    private var internalStreams: KafkaStreams? = null

    fun lateInitializeStreams(streams: KafkaStreams) {
        this.internalStreams = streams
        setupMonitoringInternal()
    }

    private val taskStateGauges: ConcurrentHashMap<String, AtomicInteger> = ConcurrentHashMap()

    data class TaskRetryState(
        val exceptionCounter: AtomicInteger = AtomicInteger(0),
        val lastExceptionTimeMillis: AtomicLong = AtomicLong(0L)
    )
    private val taskRetryTracker: ConcurrentHashMap<TaskId, TaskRetryState> = ConcurrentHashMap()

    // Configuration for UEH retry logic
    val uehInitialDelayMillis: Long = 5000
    val uehExtraOffsetToAccountForTimeToReplaceThread: Long = uehInitialDelayMillis
    val uehBackoffMultiplier: Double = 2.0
    val uehMaxDelay: Long = 60000
    val uehLoopDetectionThreshold: Int = 5

    private fun setupMonitoringInternal() {
        if (this.internalStreams != null) {
            log.info("[$applicationId] Kafka Streams instance already initialized. Skipping setup.")
            return
        }
        log.info("[$applicationId] Setting up Kafka Streams monitoring.")
        val currentStreams = this.internalStreams ?: return

        currentStreams.setStateListener { newState, oldState ->
            log.info("[$applicationId] Kafka Streams state changed from $oldState to $newState")
            if (newState == KafkaStreams.State.ERROR) {
                log.warn("[$applicationId] KafkaStreams instance entered ERROR state. Marking all known active tasks as ERROR.")
                synchronized(taskStateGauges) {
                    taskStateGauges.forEach { (taskIdStr, gaugeValue) ->
                        if (gaugeValue.getAndSet(STATE_ERROR) != STATE_ERROR) {
                            log.warn("[$applicationId] Task '$taskIdStr' set to ERROR ($STATE_ERROR) due to KafkaStreams global ERROR state.")
                        }
                    }
                }
                taskRetryTracker.clear()
            } else if (newState == KafkaStreams.State.RUNNING && oldState == KafkaStreams.State.REBALANCING) {
                log.info("[$applicationId] KafkaStreams finished REBALANCING. Triggering stale task metrics cleanup.")
                cleanupStaleTaskMetrics()
            }
        }

        currentStreams.setUncaughtExceptionHandler(this::handleUncaughtException)
    }

    // --- Uncaught Exception Handler Logic (Inlined) ---
    private fun handleUncaughtException(exception: Throwable): StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse {
        val taskId = getTaskIdFromException(exception) // Use companion object helper

        if (exception is StreamsException && taskId != null) {
            val retryState = taskRetryTracker.computeIfAbsent(taskId) { TaskRetryState() }
            val currentTime = System.currentTimeMillis()
            val lastExceptionTime = retryState.lastExceptionTimeMillis.get()
            var currentAttempt = retryState.exceptionCounter.get()

            if (lastExceptionTime != 0L &&
                (currentTime - lastExceptionTime > calculateUehDelay(currentAttempt) + uehExtraOffsetToAccountForTimeToReplaceThread)
            ) {
                log.info("[$applicationId] Task '$taskId': UEH - Exception occurred after a longer delay than expected backoff. Resetting its exception counter from $currentAttempt.")
                currentAttempt = retryState.exceptionCounter.getAndSet(0)
            }

            currentAttempt = retryState.exceptionCounter.incrementAndGet()
            retryState.lastExceptionTimeMillis.set(currentTime)

            log.error("[$applicationId] Task '$taskId': UEH - Unhandled StreamsException (attempt $currentAttempt for this task). Applying backoff and replacing thread. Cause: ${exception.cause?.message}", exception.cause ?: exception)

            if (currentAttempt >= uehLoopDetectionThreshold) {
                log.warn("[$applicationId] Task '$taskId': UEH - Has hit retry threshold ($currentAttempt >= $uehLoopDetectionThreshold). Marking as LOOPING.")
                updateTaskMetricState(taskId, STATE_LOOPING, "ueh_retry_threshold")
            }

            val delay = calculateUehDelay(currentAttempt)
            try {
                Thread.sleep(delay)
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                log.warn("[$applicationId] Task '$taskId': UEH - Thread interrupted during sleep. Proceeding with REPLACE_THREAD immediately.")
            }
            return StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.REPLACE_THREAD

        } else {
            log.error("[$applicationId] UEH - Unhandled non-StreamsException or no TaskId found. Shutting down client. Exception: ${exception.message}", exception)
            taskStateGauges.forEach { (taskIdStr, gauge) ->
                if (gauge.getAndSet(STATE_ERROR) != STATE_ERROR){
                    log.warn("Task '$taskIdStr' set to ERROR due to fatal UEH condition.")
                }
            }
            return StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.SHUTDOWN_CLIENT
        }
    }

    private fun calculateUehDelay(attempt: Int): Long {
        return min((uehInitialDelayMillis * uehBackoffMultiplier.pow(attempt - 1)).toLong(), uehMaxDelay)
    }
    // --- End of UEH Logic ---


    // Called from Processor.init()
    fun registerOrUpdateTaskGauge(taskId: TaskId) {
        val gauge = taskStateGauges.computeIfAbsent(taskMetricKey(taskId)) {
            log.info("[$applicationId] Registering health gauge for Task '$taskId'")
            val state = AtomicInteger(STATE_OK) // Default to OK
            Gauge.builder(METRIC_NAME, state) {atomicInteger -> atomicInteger.get().toDouble()}
                .tag("application_id", applicationId)
                .tag("task_id", taskId.toString())
                .description("Current health status of a Kafka Streams task. 0=OK, 1=LOOPING, 2=ERROR.")
                .register(meterRegistry)
            state
        }

        // If a task is being re-initialized (e.g., after thread replacement, or just a regular start),
        // set its state to OK, unless it was explicitly LOOPING (which the UEH might maintain).
        // If it was ERROR, re-init means we're trying again, so start as OK.
        if (gauge.get() == STATE_ERROR || gauge.get() != STATE_LOOPING) { // Reset if ERROR or was OK (and not already LOOPING)
            if (gauge.getAndSet(STATE_OK) != STATE_OK) { // Set to OK if it wasn't already
                log.info("[$applicationId] Task '$taskId' initialized/re-initialized, state set to OK.")
            }
        }
        // Also, reset its retry counter in the UEH tracker as it gets a "fresh start" for this run.
        taskRetryTracker.computeIfAbsent(taskId) { TaskRetryState() }.apply {
            // Only reset if the task is now considered OK. If it was set to LOOPING by UEH
            // and somehow re-init is called before successful processing, we might want to keep that loop counter.
            // However, typically Processor.init (calling this) means a fresh attempt.
            if (gauge.get() == STATE_OK) { // Which it should be after the lines above
                val prevCount = exceptionCounter.getAndSet(0)
                if (prevCount > 0) log.info("[$applicationId] Task '$taskId' re-initialized, resetting its UEH retry counter from $prevCount.")
                lastExceptionTimeMillis.set(0L)
            }
        }
    }

    fun updateTaskMetricState(taskId: TaskId, state: Int, reason: String = "unknown") {
        val gauge = taskStateGauges[taskMetricKey(taskId)]
        if (gauge != null) {
            if (gauge.getAndSet(state) != state) { // Only log if state actually changed
                val stateName = when(state) {
                    STATE_OK -> "OK"
                    STATE_LOOPING -> "LOOPING"
                    STATE_ERROR -> "ERROR"
                    else -> "UNKNOWN_STATE ($state)"
                }
                log.info("[$applicationId] Task '$taskId' state updated to $state ($stateName). Reason: $reason")
            }
        } else {
            // This might happen if an error occurs for a task before its Processor.init() was called
            // and registered the gauge.
            log.warn("[$applicationId] Could not find gauge for Task '$taskId' to update state to $state. Reason: $reason. Task might not have been initialized yet.")
        }
    }

    fun reportSuccessfulProcessing(taskId: TaskId) {
        // Ensure the gauge exists and set to OK
        val gauge = taskStateGauges[taskMetricKey(taskId)]
        if (gauge != null) {
            if (gauge.getAndSet(STATE_OK) != STATE_OK) { // Only log if state changed
                log.info("[$applicationId] Task '$taskId' processed successfully, state set to OK.")
            }
        } else {
            // This case should ideally not happen if registerOrUpdateTaskGauge was called in init
            log.warn("[$applicationId] Task '$taskId' reported successful processing, but no gauge found. Registering as OK.")
            // Call register which will ensure it's created and set to OK.
            // This is a fallback, init should handle primary registration.
            registerOrUpdateTaskGauge(taskId)
        }

        // If a task processes successfully, remove its retry state from the UEH tracker,
        // effectively resetting its error history for future errors.
        taskRetryTracker.remove(taskId)?.also {
            log.info("[$applicationId] Task '$taskId' processed successfully, removing its UEH retry state (had ${it.exceptionCounter.get()} errors).")
        }
    }

    fun cleanupStaleTaskMetrics() {
        val currentStreams = internalStreams ?: return // Get current streams instance
        try {
            // Check if streams is in a queryable state
            if (currentStreams.state() != KafkaStreams.State.RUNNING && currentStreams.state() != KafkaStreams.State.REBALANCING) {
                log.warn("[$applicationId] Skipping stale metrics cleanup: KafkaStreams is not in RUNNING or REBALANCING state (current: ${currentStreams.state()}).")
                return
            }
            // Query KafkaStreams for active tasks on this instance.
            // This requires the KafkaStreams instance to be in a RUNNING or REBALANCING state.
            val localThreadsMetadata = currentStreams.metadataForLocalThreads()
            val activeTaskIdsOnThisInstance = localThreadsMetadata.flatMap { threadMeta ->
                // We are interested in active tasks for health monitoring. Standby tasks don't process.
                threadMeta.activeTasks().map { it.taskId() }
            }.map { taskMetricKey(it) }.toSet() // Convert to the string key format used in our map

            val metricsToRemove = synchronized(taskStateGauges) {
                // Find task IDs in our gauges map that are NOT in the set of currently active tasks
                taskStateGauges.keys.filterNot { it in activeTaskIdsOnThisInstance }
            }

            if (metricsToRemove.isNotEmpty()) {
                log.info("[$applicationId] Cleaning up stale metrics for tasks no longer active on this instance: $metricsToRemove")
                metricsToRemove.forEach { taskIdKey ->
                    synchronized(taskStateGauges) {
                        taskStateGauges.remove(taskIdKey)
                        // Also remove from Micrometer. This is trickier.
                        // Option 1: Store Gauge objects and call meterRegistry.remove(gaugeObject)
                        // Option 2: Reconstruct the ID and call meterRegistry.remove(meterId)
                        // Option 3 (Simplest for now): Rely on Prometheus to eventually mark metrics
                        // that are no longer scraped as stale/absent if Micrometer stops exporting them
                        // (which happens if the underlying AtomicInteger becomes null or the gauge is removed).
                        // For explicit removal, you'd need to manage the Gauge instances or their exact Meter.Id.
                    }
                    // Also remove from any associated state like taskRetryTracker
                    try {
                        val taskIdObj = TaskId.parse(taskIdKey) // Reconstruct TaskId if needed for other maps
                        taskRetryTracker.remove(taskIdObj)
                    } catch (e: IllegalArgumentException) {
                        log.warn("[$applicationId] Could not parse TaskId '$taskIdKey' during cleanup of retry tracker.")
                    }
                }
            }
        } catch (e: IllegalStateException) {
            // This can happen if KafkaStreams is not in a state where metadata can be queried (e.g., ERROR, PENDING_SHUTDOWN)
            log.warn("[$applicationId] Could not retrieve local thread metadata for cleanup (KafkaStreams state: ${currentStreams.state()}): ${e.message}")
        } catch (e: Exception) {
            log.error("[$applicationId] Error during stale task metric cleanup: ${e.message}", e)
        }
    }
}