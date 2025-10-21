package kafka.consumers

import kafka.producers.KontorTilordningMelding
import kotlinx.coroutines.runBlocking
import no.nav.db.Ident
import no.nav.kafka.consumers.KontortilordningsProcessor
import no.nav.kafka.processor.Commit
import no.nav.kafka.processor.RecordProcessingResult
import no.nav.kafka.processor.Retry
import org.apache.kafka.common.serialization.Serde
import org.apache.kafka.streams.processor.api.Record
import org.slf4j.LoggerFactory

class PubliserKontorTilordningProcessor(
    val publiserKontorTilordning: suspend (kontorEndring: KontorTilordningMelding) -> Result<Unit>
) {
    val log = LoggerFactory.getLogger(this.javaClass)

    companion object {
        const val processorName = "PubliserKontorTilordningProcessor"
        val identSerde: Serde<Ident> = KontortilordningsProcessor.identSerde
        val kontortilordningSerde = jsonSerde<KontorTilordningMelding>()
    }

    fun process(
        record: Record<Ident, KontorTilordningMelding>
    ): RecordProcessingResult<String, String> {
        val result = runBlocking {
            publiserKontorTilordning(record.value())
        }
        return when (result.isSuccess) {
            true -> Commit()
            false -> {
                val message = "Kunne ikke publisere endring på ao-kontor til kafka topic: ${result.exceptionOrNull()?.message}"
                log.error(message, result.exceptionOrNull())
                Retry(message)
            }
        }
    }
}