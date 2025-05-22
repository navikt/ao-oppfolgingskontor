package no.nav.kafka.consumers

import no.nav.kafka.processor.RecordProcessingResult
import org.apache.kafka.streams.processor.api.Record
import org.apache.kafka.streams.processor.api.RecordMetadata

class OppfolgingsPeriodeConsumer {
    fun consume(record: Record<String, String>, maybeRecordMetadata: RecordMetadata?): RecordProcessingResult {
        return RecordProcessingResult.COMMIT
    }
}