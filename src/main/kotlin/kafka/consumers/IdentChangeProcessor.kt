package kafka.consumers

import kotlinx.coroutines.runBlocking
import no.nav.db.Ident
import no.nav.kafka.processor.ProcessRecord
import no.nav.kafka.processor.RecordProcessingResult
import no.nav.kafka.processor.Retry
import no.nav.person.pdl.aktor.v2.AktorProtoV2
import org.apache.kafka.streams.processor.api.Record
import org.slf4j.LoggerFactory
import services.IdentService

class IdentChangeProcessor(
    val identService: IdentService
) {
    val log = LoggerFactory.getLogger(IdentChangeProcessor::class.java)

    fun process(record: Record<String, AktorProtoV2>): RecordProcessingResult<String, AktorProtoV2> {
        runBlocking {
            runCatching {
                Ident.of(record.key())
            }
                .fold(
                { ident -> identService.hånterEndringPåIdenter(ident) },
                { error ->
                    val message = "Kunne ikke behandle endring i identer: ${error.message}"
                    log.error(message, error.message)
                    Retry<String, AktorProtoV2>(message)
                })
        }
    }
}