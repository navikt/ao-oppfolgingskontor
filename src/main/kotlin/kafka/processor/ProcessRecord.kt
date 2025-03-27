package no.nav.kafka.processor

import org.apache.kafka.streams.processor.api.Record

enum class RecordProcessingResult {
    COMMIT,
    RETRY,
    SKIP
}

typealias ProcessRecord = suspend (record: Record<String, String>) -> RecordProcessingResult
