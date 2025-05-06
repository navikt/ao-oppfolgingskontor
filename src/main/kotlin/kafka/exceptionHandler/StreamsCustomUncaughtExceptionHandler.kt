package no.nav.kafka.exceptionHandler

import org.apache.kafka.streams.errors.StreamsException
import org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler
import org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse
import kotlin.math.min
import kotlin.math.pow

class StreamsCustomUncaughtExceptionHandler : StreamsUncaughtExceptionHandler {
    val logger = org.slf4j.LoggerFactory.getLogger(StreamsCustomUncaughtExceptionHandler::class.java)
    val initialDelayMillis : Long = 5000
    val backoffMultiplier = 2.0
    val maxDelay: Long = 60000
    var exceptionCounter = 0
    var lastExceptionTimeMillis = 0L
    override fun handle(exception: Throwable): StreamThreadExceptionResponse {
        if (exception is StreamsException) {
            val currentTime = System.currentTimeMillis()
            if (lastExceptionTimeMillis != 0L && currentTime - lastExceptionTimeMillis + 100L> calculateDelay(exceptionCounter)) {
                logger.info("Exception occurred after delay. Resetting exception counter.")
                exceptionCounter = 0
            }
            exceptionCounter++
            lastExceptionTimeMillis = currentTime

            val originalException = exception.cause
            logger.error("Unhandled exception in stream processor. Retrying processing after delay", originalException)
            Thread.sleep(calculateDelay(exceptionCounter))
            return StreamThreadExceptionResponse.REPLACE_THREAD
        }
        logger.error("Unhandled exception outside stream processor. Shutting down streams client.", exception)
        // If the exception is not a StreamsException, we assume it's a fatal error and shut down the client
        // TODO alert
        return StreamThreadExceptionResponse.SHUTDOWN_CLIENT
    }
    fun calculateDelay(attempt: Int): Long {
        return min((initialDelayMillis * backoffMultiplier.pow(attempt - 1)).toLong(), maxDelay)
    }
}