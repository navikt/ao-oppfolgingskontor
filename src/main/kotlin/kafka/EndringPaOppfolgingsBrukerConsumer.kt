package no.nav.kafka

import no.nav.kafka.processor.RecordProcessingResult
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.apache.kafka.streams.processor.api.Record
import javax.sql.DataSource

class EndringPaOppfolgingsBrukerConsumer(
    val dataSource: DataSource,
) {
    val log = LoggerFactory.getLogger(EndringPaOppfolgingsBrukerConsumer::class.java)

    fun consume(record: Record<String, String>): RecordProcessingResult {
        log.info("Consumed record")
        return RecordProcessingResult.COMMIT
    }
}