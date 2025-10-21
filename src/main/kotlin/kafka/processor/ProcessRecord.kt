package no.nav.kafka.processor

import org.apache.kafka.streams.processor.api.Record

sealed class RecordProcessingResult<KOut, VOut>

class Forward<K, V>(
    val forwardedRecord: Record<K, V>,
    /* Hvis denne ikke oppgis går melding videre til neste prosessor i "chain"-en */
    val topic: String? = null
): RecordProcessingResult<K, V>()
class Commit<K, V>: RecordProcessingResult<K, V>()
class Skip<K, V>: RecordProcessingResult<K, V>()
class Retry<K, V>(val reason: String): RecordProcessingResult<K, V>()

typealias ProcessRecord <K, V, KOut, VOut> = (record: Record<K, V>) -> RecordProcessingResult<KOut, VOut>
