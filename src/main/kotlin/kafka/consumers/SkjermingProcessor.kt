package no.nav.kafka.consumers

import kotlinx.coroutines.runBlocking
import no.nav.db.Ident
import no.nav.db.IdentSomKanLagres
import no.nav.domain.HarSkjerming
import no.nav.domain.externalEvents.SkjermetStatusEndret
import no.nav.http.client.IdentIkkeFunnet
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
            runCatching { Ident.of(fnr, Ident.HistoriskStatus.UKJENT) }
                .fold( { validFnr ->
                    val ident = validFnr as? IdentSomKanLagres
                        ?: throw IllegalStateException("Key i skjermings-topic var aktorId")
                    val result = automatiskKontorRutingService.handterEndringISkjermingStatus(
                        SkjermetStatusEndret(ident, HarSkjerming(skjermingStatus))
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
    val endringer: KontorEndringer
)