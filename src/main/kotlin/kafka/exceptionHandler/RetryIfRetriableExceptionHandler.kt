package no.nav.kafka.exceptionHandler

import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.errors.RetriableException
import org.apache.kafka.streams.errors.ProductionExceptionHandler

class RetryIfRetriableExceptionHandler : ProductionExceptionHandler {
    override fun handle(record: ProducerRecord<ByteArray, ByteArray>?, exception: Exception?): ProductionExceptionHandler.ProductionExceptionHandlerResponse {
        return if (exception is RetriableException) {
            println("Retrying due to transient error: ${exception.message}")
            ProductionExceptionHandler.ProductionExceptionHandlerResponse.RETRY
        } else {
            println("Fatal error, skipping message: ${exception?.message}")
            ProductionExceptionHandler.ProductionExceptionHandlerResponse.FAIL
        }
    }

    override fun configure(configs: MutableMap<String, *>?) {}
}