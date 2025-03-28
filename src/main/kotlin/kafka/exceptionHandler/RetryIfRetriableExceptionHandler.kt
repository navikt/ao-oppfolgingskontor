package no.nav.kafka.exceptionHandler

import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.errors.RetriableException
import org.apache.kafka.streams.errors.ErrorHandlerContext
import org.apache.kafka.streams.errors.ProductionExceptionHandler
import org.apache.kafka.streams.errors.ProductionExceptionHandler.ProductionExceptionHandlerResponse

class RetryIfRetriableExceptionHandler : ProductionExceptionHandler {

    override fun handle(
        context: ErrorHandlerContext,
        record: ProducerRecord<ByteArray, ByteArray>,
        exception: Exception
    ): ProductionExceptionHandlerResponse {
        return if (exception is RetriableException) {
            println("Retrying due to transient error: ${exception.message}")
            return ProductionExceptionHandlerResponse.RETRY
        } else {
            println("Fatal error, skipping message: ${exception?.message}")
            return ProductionExceptionHandlerResponse.FAIL
        }
    }

    override fun configure(configs: MutableMap<String, *>?) {}
}