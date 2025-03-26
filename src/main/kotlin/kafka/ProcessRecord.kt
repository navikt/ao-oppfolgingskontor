package no.nav.kafka

import org.apache.kafka.streams.processor.api.Record

enum class RecordProcessingResult {
    COMMIT,
    RETRY,
    FAIL
}

typealias ProcessRecord = (record: Record<String, String>) -> RecordProcessingResult
