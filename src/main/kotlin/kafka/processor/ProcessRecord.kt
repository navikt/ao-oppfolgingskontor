package no.nav.kafka.processor

import org.apache.kafka.streams.processor.api.Record
import org.apache.kafka.streams.processor.api.RecordMetadata

enum class RecordProcessingResult {
    COMMIT,
    RETRY,
    SKIP
}

typealias ProcessRecord <K, V> = (record: Record<K, V>, maybeRecordMetadata: RecordMetadata?) -> RecordProcessingResult
