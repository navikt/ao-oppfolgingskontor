package no.nav.kafka.config

import kotlinx.coroutines.*
import no.nav.kafka.KafkaStreamsInstance
import org.apache.kafka.streams.KafkaStreams
import org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.pow

class StreamsLifecycleManager(
    dispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private val log = LoggerFactory.getLogger(javaClass)

    val restartCounters = ConcurrentHashMap<String, AtomicInteger>()
    val maxRestarts = 5
    val initialDelayMillis = 5000L
    val backoffMultiplier = 2.0
    val shutDownTimeout = Duration.ofSeconds(20)

    // Eget CoroutineScope for restart-jobber
    val lifecycleScope = CoroutineScope(dispatcher + SupervisorJob() + CoroutineName("StreamsLifecycleManager"))

//    fun startAll() {
//    Skal vi holde alle streams instanser i en liste?
//    }
//
//    fun stopAll() {
//        Skal vi holde alle streams instanser i en liste?
//    }

    fun setupStreamsInstanceLifecycleHandler(kafkaStreamsInstance: KafkaStreamsInstance) {
        kafkaStreamsInstance.streams.setStateListener { newState, oldState ->
            log.info("${kafkaStreamsInstance.name} Streams state changed from $oldState to $newState")
            when (newState) {
                KafkaStreams.State.ERROR -> {
                    log.error("${kafkaStreamsInstance.name} Streams entered ERROR state. Attempting restart.")
                    kafkaStreamsInstance.isRunningFlag.set(false)
                    scheduleRestart(kafkaStreamsInstance)
                }
                KafkaStreams.State.RUNNING -> {
                    restartCounters.computeIfAbsent(kafkaStreamsInstance.name) { AtomicInteger(0) }.set(0)
                    kafkaStreamsInstance.isRunningFlag.set(true)
                    log.info("${kafkaStreamsInstance.name} is RUNNING successfully.")
                }
                KafkaStreams.State.PENDING_SHUTDOWN,
                KafkaStreams.State.NOT_RUNNING -> {
                    if (kafkaStreamsInstance.isRunningFlag.get() && oldState != KafkaStreams.State.PENDING_SHUTDOWN) { // Sjekk om den *skulle* kjørt
                        log.warn("${kafkaStreamsInstance.name} Streams entered $newState state unexpectedly. Scheduling check/restart.")
                        // Vurder om scheduleRestart er riktig her, eller om det skyldes en initiell feil el.l.
                        // scheduleRestart(appName, streams, isRunningFlag)
                        kafkaStreamsInstance.isRunningFlag.set(false) // Den kjører ihvertfall ikke nå
                    } else {
                        log.info("${kafkaStreamsInstance.name} Streams is in state $newState (expected during shutdown or before start).")
                    }
                }
                else -> {
                    log.debug("${kafkaStreamsInstance.name} Streams is in state $newState (ignoring).")
                } // Ignorer andre tilstander
            }
        }

        kafkaStreamsInstance.streams.setUncaughtExceptionHandler { throwable ->
            log.error("Uncaught exception in ${kafkaStreamsInstance.name} Streams thread. State should transition to ERROR.", throwable)
            kafkaStreamsInstance.isRunningFlag.set(false)
            StreamThreadExceptionResponse.SHUTDOWN_APPLICATION
            // StateListener vil normalt ta over her når tilstanden blir ERROR
        }
    }

    fun startStreamsApp(kafkaStreamsInstance: KafkaStreamsInstance) {
        if (kafkaStreamsInstance.isRunningFlag.get()) {
            log.warn("${kafkaStreamsInstance.name} is already marked as running, skipping start request.")
            return
        }
        // Valgfritt: Rydd opp state før *første* start (VÆR FORSIKTIG!)
        // try { streams.cleanUp() } catch (e: Exception) { log.warn("Cleanup failed for $appName (maybe not needed): ${e.message}")}

        try {
            log.info("Attempting to start ${kafkaStreamsInstance.name} Streams...")
            kafkaStreamsInstance.streams.start()
            kafkaStreamsInstance.isRunningFlag.set(true) // Optimistisk, StateListener korrigerer
        } catch (e: Exception) {
            log.error("Failed to start ${kafkaStreamsInstance.name} Streams initially.", e)
            kafkaStreamsInstance.isRunningFlag.set(false)
            scheduleRestart(kafkaStreamsInstance) // Prøv å restarte hvis initiell start feiler
        }
    }

    fun scheduleRestart(kafkaStreamsInstance: KafkaStreamsInstance) {
        if (shouldSkipRestart(kafkaStreamsInstance)) return

        val attempt = restartCounters.computeIfAbsent(kafkaStreamsInstance.name) { AtomicInteger(0) }.incrementAndGet()

        if (attempt > maxRestarts) {
            log.error("${kafkaStreamsInstance.name} Streams restart failed after $maxRestarts attempts. Giving up.")
            return
        }

        val delayMillis = calculateDelay(attempt)
        log.warn("${kafkaStreamsInstance.name} scheduling restart attempt $attempt/$maxRestarts after ${delayMillis}ms delay.")

        lifecycleScope.launch {
            try {
                delay(delayMillis)

                // Dobbeltsjekk at vi fortsatt skal restarte (f.eks. at appen ikke er stoppet)
                if (!isActive || kafkaStreamsInstance.isRunningFlag.get()) {
                    log.info("${kafkaStreamsInstance.name} restart attempt $attempt cancelled (scope inactive or already running).")
                    return@launch
                }

                log.info("Restarting ${kafkaStreamsInstance.name} Streams (Attempt $attempt)...")

                val closed = closeStreamsInstance(kafkaStreamsInstance, attempt)

                kafkaStreamsInstance.isRunningFlag.set(false) // Sørg for at den er markert som ikke-kjørende

                // Valgfritt: cleanUp() (VÆR FORSIKTIG)
                // log.warn("$appName: Cleaning up local state store before restart.")
                // try { streams.cleanUp() } catch (e: Exception) { log.error("Cleanup failed during restart for $appName", e)}

                // 2. Start på nytt
                if (closed) {
                    log.debug("${kafkaStreamsInstance.name}: Starting streams instance after close...")
                    kafkaStreamsInstance.streams.start()
                    kafkaStreamsInstance.isRunningFlag.set(true) // Optimistisk igjen
                    log.info("${kafkaStreamsInstance.name} Streams restarted successfully on attempt $attempt.")
                }
                // StateListener vil nullstille counter når RUNNING nås

            } catch (e: CancellationException) {
                log.info("${kafkaStreamsInstance.name} restart attempt $attempt cancelled.", e)
                kafkaStreamsInstance.isRunningFlag.set(false)
            } catch (e: Exception) {
                handleRestartFailure(kafkaStreamsInstance, attempt, e)
            }
        }
    }

    private fun shouldSkipRestart(kafkaStreamsInstance: KafkaStreamsInstance): Boolean {
        if (!lifecycleScope.isActive) {
            log.warn("Lifecycle scope is inactive, skipping restart schedule for ${kafkaStreamsInstance.name}.")
            return true
        }
        return false
    }

    private fun calculateDelay(attempt: Int): Long {
        return (initialDelayMillis * backoffMultiplier.pow(attempt - 1)).toLong()
    }

    private suspend fun closeStreamsInstance(kafkaStreamsInstance: KafkaStreamsInstance, attempt: Int): Boolean {
        log.debug("${kafkaStreamsInstance.name}: Closing streams instance before restart...")
        return withTimeoutOrNull(shutDownTimeout.plusSeconds(5).toMillis()) {
            kafkaStreamsInstance.streams.close(shutDownTimeout)
            true
        } ?: run {
            log.warn("${kafkaStreamsInstance.name}: Streams close timed out during restart attempt $attempt.")
            false
        }
    }

    private fun handleRestartFailure(kafkaStreamsInstance: KafkaStreamsInstance, attempt: Int, exception: Exception) {
        log.error("${kafkaStreamsInstance.name}: Error during restart process (Attempt $attempt).", exception)
        kafkaStreamsInstance.isRunningFlag.set(false)
        if (attempt < maxRestarts && lifecycleScope.isActive) {
            scheduleRestart(kafkaStreamsInstance)
        } else {
            log.error("${kafkaStreamsInstance.name}: Final restart attempt ($attempt) failed or scope inactive. Giving up.", exception)
        }
    }

    fun closeStreamsInstance(kafkaStreamsInstance: KafkaStreamsInstance) {
        val wasRunning = kafkaStreamsInstance.isRunningFlag.getAndSet(false) // Marker at vi stopper den aktivt
        if (kafkaStreamsInstance.streams.state() != KafkaStreams.State.NOT_RUNNING && kafkaStreamsInstance.streams.state() != KafkaStreams.State.PENDING_SHUTDOWN) {
            if (wasRunning) {
                log.info("Closing ${kafkaStreamsInstance.name} streams...")
                try {
                    // Bruk runBlocking eller en annen mekanisme hvis dette er i shutdown hook
                    // og trenger å fullføre synkront. Her antar vi at Ktor gir oss tid.
                    val closed = kafkaStreamsInstance.streams.close(shutDownTimeout)
                    if (closed) {
                        log.info("${kafkaStreamsInstance.name} streams closed successfully.")
                    } else {
                        log.warn("${kafkaStreamsInstance.name} streams close timed out during shutdown.")
                    }
                } catch (e: Exception) {
                    log.error("Error closing ${kafkaStreamsInstance.name} streams during shutdown.", e)
                }
            } else {
                log.info("${kafkaStreamsInstance.name} streams was not running, skipping close call.")
            }
        } else {
            log.info("${kafkaStreamsInstance.name} streams already in state ${kafkaStreamsInstance.streams.state()}, no close action needed.")
        }
        lifecycleScope.cancel() // Avslutt coroutine-scope for restart-jobber
    }
}