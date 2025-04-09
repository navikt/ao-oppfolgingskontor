package no.nav.kafka.exceptionHandler

import no.nav.no.nav.kafka.exceptionHandler.FunksjonellFeilException
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.errors.RetriableException
import org.apache.kafka.streams.errors.ErrorHandlerContext
import org.apache.kafka.streams.errors.ProcessingExceptionHandler
import org.apache.kafka.streams.errors.ProductionExceptionHandler
import org.apache.kafka.streams.errors.ProductionExceptionHandler.ProductionExceptionHandlerResponse
import org.apache.kafka.streams.processor.api.Record

class RetryIfRetriableExceptionHandler : ProcessingExceptionHandler {

    override fun configure(configs: MutableMap<String, *>?) {}
    override fun handle(
        context: ErrorHandlerContext?,
        kafkaRecord: Record<*, *>?,
        exception: java.lang.Exception?
    ): ProcessingExceptionHandler.ProcessingHandlerResponse {
        return if (exception is RetriableException) {
            println("Retrying due to transient error: ${exception.message}")
            return ProcessingExceptionHandler.ProcessingHandlerResponse.FAIL
        } else if (exception is FunksjonellFeilException) {
            println("Fatal error, skipping message: ${exception?.message}")
            return ProcessingExceptionHandler.ProcessingHandlerResponse.CONTINUE
        } else {
            println("Intermittent error, retrying message: ${exception?.message}")
            return ProcessingExceptionHandler.ProcessingHandlerResponse.FAIL
        }
    }
}