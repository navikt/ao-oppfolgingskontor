package no.nav.kafka.consumers

import kotlinx.coroutines.runBlocking
import no.nav.db.Fnr
import no.nav.domain.HarSkjerming
import no.nav.domain.events.KontorEndretEvent
import no.nav.domain.externalEvents.SkjermetStatusEndret
import no.nav.kafka.processor.Commit
import no.nav.kafka.processor.RecordProcessingResult
import no.nav.kafka.processor.Retry
import no.nav.kafka.processor.Skip
import no.nav.services.AutomatiskKontorRutingService
import org.apache.kafka.streams.processor.api.Record
import org.slf4j.LoggerFactory

class SkjermingProcessor(
    val automatiskKontorRutingService: AutomatiskKontorRutingService
) {
    val log = LoggerFactory.getLogger(SkjermingProcessor::class.java)

    fun process(record: Record<String, String>): RecordProcessingResult<String, String> {
        println("Processing Skjerming record: ${record.value()}")
        return handterEndringISKjermetStatus(record.key(), record.value().toBoolean())
    }

    fun handterEndringISKjermetStatus(fnr: String, skjermingStatus: Boolean): RecordProcessingResult<String, String> {
        return runBlocking {
            runCatching { Fnr(fnr) }
                .fold( { validFnr ->
                    val result = automatiskKontorRutingService.handterEndringISkjermingStatus(
                        SkjermetStatusEndret(validFnr, HarSkjerming(skjermingStatus))
                    )
                    when (result.isSuccess) {
                        true -> {
                            log.info("Behandling endring i skjerming med resultat: ${result.getOrNull()}")
                            Commit()
                        }
                        false -> {
                            val exception = result.exceptionOrNull()
                            log.error("Kunne ikke behandle melding om endring i skjermingstatus", exception)
                            Retry("Kunne ikke behandle melding om endring i skjermingstatus: ${exception?.message}")
                        }
                    }
                },
                 { e ->
                     log.warn("Mottak ident på skjerming-topic som ikke var gyldig, hopper over", e)
                     Skip()
                })
        }
    }
}

data class EndringISkjermingResult(
    val endringer: List<KontorEndretEvent>
)