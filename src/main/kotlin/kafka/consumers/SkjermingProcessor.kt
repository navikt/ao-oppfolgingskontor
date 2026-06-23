package no.nav.kafka.consumers

import kafka.out.toOppfolgingEndretTilordningMeldingRecord
import kafka.producers.OppfolgingEndretTilordningMelding
import kotlinx.coroutines.runBlocking
import no.nav.db.Ident
import no.nav.db.IdentSomKanLagres
import no.nav.domain.HarSkjerming
import no.nav.domain.OppfolgingsperiodeId
import no.nav.domain.externalEvents.SkjermetStatusEndret
import no.nav.kafka.processor.Commit
import no.nav.kafka.processor.Forward
import no.nav.kafka.processor.RecordProcessingResult
import no.nav.kafka.processor.Retry
import no.nav.kafka.processor.Skip
import no.nav.services.AutomatiskKontorRutingService
import no.nav.services.KontorTilordningService
import org.apache.kafka.streams.processor.api.Record
import org.slf4j.LoggerFactory

class SkjermingProcessor(
    val automatiskKontorRutingService: AutomatiskKontorRutingService,
    val kontorTilordningService: KontorTilordningService,
) {
    val log = LoggerFactory.getLogger(SkjermingProcessor::class.java)

    fun process(record: Record<String, String?>): RecordProcessingResult<OppfolgingsperiodeId, OppfolgingEndretTilordningMelding> {
        println("Processing Skjerming record: ${record.value()}")
        return handterEndringISKjermetStatus(record.key(), record.value()?.toBoolean())
    }

    fun handterEndringISKjermetStatus(fnr: String, skjermingStatus: Boolean?): RecordProcessingResult<OppfolgingsperiodeId, OppfolgingEndretTilordningMelding> {
        if (skjermingStatus == null) {
            log.warn("Skjermingsmelding hadde null i payload, hopper over melding")
            return Skip()
        }
        return runBlocking {
            runCatching { Ident.validateOrThrow(fnr, Ident.HistoriskStatus.UKJENT) }
                .fold( { validFnr ->
                    val ident = validFnr as? IdentSomKanLagres
                        ?: throw IllegalStateException("Key i skjermings-topic var aktorId")
                    val result = automatiskKontorRutingService.handterEndringISkjermingStatus(
                        SkjermetStatusEndret(ident, HarSkjerming(skjermingStatus))
                    )
                    when (result) {
                        is EndringISkjermingSuccess -> {
                            log.info("Behandling endring i skjerming med resultat: $result")
                            kontorTilordningService.tilordneKontor(result.endringer)
                            if (result.endringer.aoKontorEndret != null) {
                                val record = result.endringer.aoKontorEndret.toOppfolgingEndretTilordningMeldingRecord()
                                Forward(record)
                            } else {
                                log.error("Fikk melding om endring av skjermet men klarte ikke å sette nytt kontor: $result")
                                Retry("Fikk melding om endring av skjermet men klarte ikke å sette nytt kontor: $result")
                            }
                        }
                        EndringISkjermingBrukerIkkeUnderOppfølging -> {
                            log.info("Fikk melding om skjerming for bruker som ikke er under oppfølging, ignorerer melding")
                            Commit()
                        }
                        is EndringISkjermingBehandlingFeilet -> {
                            log.error("Kunne ikke behandle melding om endring i skjermingstatus", result.exception)
                            Retry("Kunne ikke behandle melding om endring i skjermingstatus: ${result.exception.message}")
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

sealed interface EndringISkjermingResult

data class EndringISkjermingSuccess(
    val endringer: KontorEndringer
): EndringISkjermingResult

object EndringISkjermingBrukerIkkeUnderOppfølging: EndringISkjermingResult
data class EndringISkjermingBehandlingFeilet(val exception: Exception): EndringISkjermingResult
