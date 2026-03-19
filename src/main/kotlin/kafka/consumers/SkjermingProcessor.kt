package no.nav.kafka.consumers

import java.time.ZonedDateTime
import kafka.producers.OppfolgingEndretTilordningMelding
import kotlinx.coroutines.runBlocking
import no.nav.db.Ident
import no.nav.db.IdentSomKanLagres
import no.nav.domain.HarSkjerming
import no.nav.domain.OppfolgingsperiodeId
import no.nav.domain.events.AOKontorEndret
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
    val brukAoRuting: Boolean,
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
                    when (result.isSuccess) {
                        true -> {
                            val endringResult = result.getOrNull()
                            log.info("Behandling endring i skjerming med resultat: $endringResult")
                            if (endringResult != null) {
                                endringResult.let { kontorTilordningService.tilordneKontor(it.endringer, brukAoRuting) }
                                if (brukAoRuting && endringResult.endringer.aoKontorEndret != null) {
                                    val record = endringResult.endringer.aoKontorEndret.toOppfolgingEndretTilordningMeldingRecord()
                                    Forward(record)
                                } else {
                                    log.info("Produserer ikke melding ved endring i skjerming fordi funkjsonaliteten er togglet av")
                                    Commit()
                                }
                            } else {
                                Commit()
                            }
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

fun AOKontorEndret.toOppfolgingEndretTilordningMeldingRecord(): Record<OppfolgingsperiodeId, OppfolgingEndretTilordningMelding> {
    return Record(
        this.tilordning.oppfolgingsperiodeId,
        OppfolgingEndretTilordningMelding(
            kontorId = this.tilordning.kontorId.id,
            oppfolgingsperiodeId = this.tilordning.oppfolgingsperiodeId.value.toString(),
            ident = this.tilordning.fnr.value,
            kontorEndringsType = this.kontorEndringsType(),
        ),
        ZonedDateTime.now().toEpochSecond(),
    )
}

data class EndringISkjermingResult(
    val endringer: KontorEndringer
)