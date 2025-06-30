package no.nav.kafka.consumers

import kotlinx.coroutines.runBlocking
import no.nav.db.Fnr
import no.nav.kafka.processor.RecordProcessingResult
import no.nav.services.AutomatiskKontorRutingService
import org.apache.kafka.streams.processor.api.Record
import org.slf4j.LoggerFactory

class SkjermingConsumer(
    val automatiskKontorRutingService: AutomatiskKontorRutingService
) {
    val log = LoggerFactory.getLogger(SkjermingConsumer::class.java)

    fun consume(record: Record<String, String>, maybeRecordMetadata: Any?): RecordProcessingResult {
        println("Processing Skjerming record: ${record.value()}")
        return handterEndringISKjermetStatus(record.key(), record.value().toBoolean())
    }

    fun handterEndringISKjermetStatus(fnr: String, skjermingStatus: Boolean): RecordProcessingResult {
        return runBlocking {
            val result = automatiskKontorRutingService.handterEndringISkjermingStatus(
                EndringISkjermingStatus(fnr, skjermingStatus)
            )
            when (result.isSuccess) {
                true -> {
                    log.info("Behandling endring i skjerming med resultat: ${result.getOrNull()}")
                    RecordProcessingResult.COMMIT
                }
                false -> {
                    log.error("Kunne ikke behandle melding om endring i skjermingstatus", result.exceptionOrNull())
                    RecordProcessingResult.RETRY
                }
            }
        }
    }
}

class EndringISkjermingStatus(
    val fnr: Fnr,
    val erSkjermet: Boolean
)

enum class EndringISkjermingResult {
    NY_ENHET,
    IKKE_NY_ENHET,
}