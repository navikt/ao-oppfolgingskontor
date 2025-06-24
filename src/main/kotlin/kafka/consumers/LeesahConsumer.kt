package no.nav.kafka.consumers

import no.nav.kafka.processor.RecordProcessingResult
import no.nav.person.pdl.leesah.Personhendelse
import org.apache.kafka.streams.processor.api.Record
import org.apache.kafka.streams.processor.api.RecordMetadata
import org.slf4j.LoggerFactory

class LeesahConsumer() {
    val log = LoggerFactory.getLogger(this::class.java)

    fun consume(record: Record<String, Personhendelse>, maybeRecordMetadata: RecordMetadata?): RecordProcessingResult {
        log.info("Consumer leesah record ${record.value().opplysningstype} ${record.value().endringstype}")
        return RecordProcessingResult.COMMIT
    }
}