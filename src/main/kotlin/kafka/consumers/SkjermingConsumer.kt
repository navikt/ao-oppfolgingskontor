package no.nav.kafka.consumers

import kotlinx.coroutines.runBlocking
import no.nav.db.Fnr
import no.nav.domain.HarSkjerming
import no.nav.domain.events.KontorEndretEvent
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
                SkjermetStatusEndret(Fnr(fnr), HarSkjerming(skjermingStatus))
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

data class EndringISkjermingResult(
    val endringer: List<KontorEndretEvent>
)