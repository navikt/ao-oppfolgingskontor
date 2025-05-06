package no.nav.kafka.exceptionHandler

import org.apache.kafka.streams.errors.StreamsException
import org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler
import org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse

class StreamsCustomUncaughtExceptionHandler : StreamsUncaughtExceptionHandler {
    val logger = org.slf4j.LoggerFactory.getLogger(StreamsCustomUncaughtExceptionHandler::class.java)
    override fun handle(exception: Throwable): StreamThreadExceptionResponse {
        if (exception is StreamsException) {
            val originalException = exception.cause
            logger.error("Unhandled exception in stream processor. Retrying processing: ${originalException?.message}", exception)
            Thread.sleep(5000)
            return StreamThreadExceptionResponse.REPLACE_THREAD
        }
        logger.error("Unhandled exception outside stream processor. Shutting down streams client: ${exception.message}", exception)
        // If the exception is not a StreamsException, we assume it's a fatal error and shut down the client
        // TODO alert
        return StreamThreadExceptionResponse.SHUTDOWN_CLIENT
    }
}