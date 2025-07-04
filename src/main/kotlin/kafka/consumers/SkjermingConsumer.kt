package no.nav.kafka.consumers

import kotlinx.coroutines.runBlocking
import no.nav.domain.externalEvents.SkjermetStatusEndret
import no.nav.kafka.processor.Commit
import no.nav.kafka.processor.RecordProcessingResult
import no.nav.kafka.processor.Retry
import no.nav.services.AutomatiskKontorRutingService
import org.apache.kafka.streams.processor.api.Record
import org.slf4j.LoggerFactory

class SkjermingConsumer(
    val automatiskKontorRutingService: AutomatiskKontorRutingService
) {
    val log = LoggerFactory.getLogger(SkjermingConsumer::class.java)

    fun consume(record: Record<String, String>, maybeRecordMetadata: Any?): RecordProcessingResult<Unit, Unit> {
        println("Processing Skjerming record: ${record.value()}")
        return handterEndringISKjermetStatus(record.key(), record.value().toBoolean())
    }

    fun handterEndringISKjermetStatus(fnr: String, skjermingStatus: Boolean): RecordProcessingResult<Unit, Unit> {
        return runBlocking {
            val result = automatiskKontorRutingService.handterEndringISkjermingStatus(
                SkjermetStatusEndret(fnr, skjermingStatus)
            )
            when (result.isSuccess) {
                true -> {
                    log.info("Behandling endring i skjerming med resultat: ${result.getOrNull()}")
                    Commit
                }
                false -> {
                    val exception = result.exceptionOrNull()
                    log.error("Kunne ikke behandle melding om endring i skjermingstatus", exception)
                    Retry("Kunne ikke behandle melding om endring i skjermingstatus: ${exception?.message}")
                }
            }
        }
    }
}

enum class EndringISkjermingResult {
    NY_ENHET,
    IKKE_NY_ENHET,
}