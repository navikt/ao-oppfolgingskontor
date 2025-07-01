package no.nav.kafka.processor

import org.apache.kafka.streams.processor.api.Record
import org.apache.kafka.streams.processor.api.RecordMetadata

sealed class RecordProcessingResult<KOut, VOut>

class Forward<K, V>(val forwardedRecord: Record<K, V>): RecordProcessingResult<K, V>()
object Commit: RecordProcessingResult<Unit, Unit>()
object Skip: RecordProcessingResult<Unit, Unit>()
class Retry(val reason: String): RecordProcessingResult<Unit, Unit>()

typealias ProcessRecord <K, V, KOut, VOut> = (record: Record<K, V>, maybeRecordMetadata: RecordMetadata?) -> RecordProcessingResult<KOut, VOut>
