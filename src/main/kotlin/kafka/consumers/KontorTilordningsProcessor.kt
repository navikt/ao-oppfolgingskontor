package no.nav.kafka.consumers

import kotlinx.coroutines.runBlocking
import no.nav.db.Ident
import no.nav.domain.externalEvents.OppfolgingsperiodeStartet
import no.nav.http.client.FnrResult
import no.nav.kafka.processor.Commit
import no.nav.kafka.processor.RecordProcessingResult
import no.nav.kafka.processor.Retry
import no.nav.kafka.processor.Skip
import no.nav.services.AutomatiskKontorRutingService
import no.nav.services.TilordningFeil
import no.nav.services.TilordningSuccess
import org.apache.kafka.streams.processor.api.Record
import org.slf4j.LoggerFactory

class KontorTilordningsProcessor(
        private val automatiskKontorRutingService: AutomatiskKontorRutingService,
        private val skipPersonIkkeFunnet: Boolean = false,
) {
    companion object {
        const val processorName = "OppfolgingsPeriodeProcessor"
    }

    private val log = LoggerFactory.getLogger(this::class.java)
    fun consume(
            record: Record<Ident, OppfolgingsperiodeStartet>
    ): RecordProcessingResult<String, String> {
        try {
            return runBlocking {
                val oppfolgingsperiode = record.value()
                return@runBlocking automatiskKontorRutingService
                    .tilordneKontorAutomatisk(oppfolgingsperiode)
                    .let { tilordningResultat ->
                        when (tilordningResultat) {
                            is TilordningFeil -> {
                                if (skipPersonIkkeFunnet && tilordningResultat.message.contains("Ingen foedselsdato i felt 'foedselsdato' fra pdl-spørring, dårlig data i dev?")) {
                                    log.info("Fant ikke alder på person i dev - hopper over melding")
                                    Skip()
                                } else {
                                    val melding = "Kunne ikke tilordne kontor ved start på oppfølgingspeiode: ${tilordningResultat.message}"
                                    log.error(melding)
                                    Retry(melding)
                                }
                            }
                            is TilordningSuccess -> Commit()
                        }
                    }
            }
        } catch (e: Exception) {
            val feilmelding = "Klarte ikke behandle oppfolgingsperiode melding: ${e.message}"
            log.error(feilmelding, e)
            return Retry(feilmelding)
        }
    }
}
