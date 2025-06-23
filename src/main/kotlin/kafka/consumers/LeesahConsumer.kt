package no.nav.kafka.consumers

import no.nav.kafka.processor.LeesahDto
import no.nav.kafka.processor.RecordProcessingResult
import org.apache.kafka.streams.processor.api.Record
import org.apache.kafka.streams.processor.api.RecordMetadata
import org.slf4j.LoggerFactory

class LeesahConsumer() {
    val log = LoggerFactory.getLogger(this::class.java)

    fun consume(record: Record<String, LeesahDto>, maybeRecordMetadata: RecordMetadata?): RecordProcessingResult {
        log.info("Consumer leesah record")
        return RecordProcessingResult.COMMIT
    }
}