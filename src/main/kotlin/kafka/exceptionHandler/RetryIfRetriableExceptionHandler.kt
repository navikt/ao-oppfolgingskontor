package no.nav.kafka.exceptionHandler

import no.nav.no.nav.kafka.exceptionHandler.FunksjonellFeilException
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.errors.RetriableException
import org.apache.kafka.streams.errors.ErrorHandlerContext
import org.apache.kafka.streams.errors.ProcessingExceptionHandler
import org.apache.kafka.streams.errors.ProductionExceptionHandler
import org.apache.kafka.streams.errors.ProductionExceptionHandler.ProductionExceptionHandlerResponse
import org.apache.kafka.streams.processor.api.Record
import org.slf4j.LoggerFactory

class RetryIfRetriableExceptionHandler : ProcessingExceptionHandler {

    val log = LoggerFactory.getLogger(RetryIfRetriableExceptionHandler::class.java)

    override fun configure(configs: MutableMap<String, *>?) {}
    override fun handle(
        context: ErrorHandlerContext?,
        kafkaRecord: Record<*, *>?,
        exception: java.lang.Exception?
    ): ProcessingExceptionHandler.ProcessingHandlerResponse {
        return if (exception is RetriableException) {
            log.error("Retrying due to transient error: $exception", exception)
            return ProcessingExceptionHandler.ProcessingHandlerResponse.FAIL
        } else if (exception is FunksjonellFeilException) {
            log.error("Fatal error, skipping message: $exception", exception)
            return ProcessingExceptionHandler.ProcessingHandlerResponse.CONTINUE
        } else {
            log.error("Intermittent error, retrying message: $exception", exception)
            return ProcessingExceptionHandler.ProcessingHandlerResponse.FAIL
        }
    }
}