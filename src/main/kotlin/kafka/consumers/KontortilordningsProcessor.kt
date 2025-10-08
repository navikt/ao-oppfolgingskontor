package no.nav.kafka.consumers

import kafka.consumers.jsonSerde
import kotlinx.coroutines.runBlocking
import no.nav.db.Ident
import no.nav.domain.externalEvents.OppfolgingsperiodeStartet
import no.nav.kafka.processor.Commit
import no.nav.kafka.processor.RecordProcessingResult
import no.nav.kafka.processor.Retry
import no.nav.kafka.processor.Skip
import no.nav.services.AutomatiskKontorRutingService
import no.nav.services.TilordningFeil
import no.nav.services.TilordningRetry
import no.nav.services.TilordningSuccess
import no.nav.services.TilordningSuccessIngenEndring
import no.nav.services.TilordningSuccessKontorEndret
import org.apache.kafka.common.serialization.Deserializer
import org.apache.kafka.common.serialization.Serde
import org.apache.kafka.common.serialization.Serializer
import org.apache.kafka.streams.processor.api.Record
import org.slf4j.LoggerFactory

class KontortilordningsProcessor(
        private val automatiskKontorRutingService: AutomatiskKontorRutingService,
        private val skipPersonIkkeFunnet: Boolean = false,
) {
    companion object {
        const val processorName = "KontortilordningsProcessor"

        val identSerde: Serde<Ident> = object : Serde<Ident> {
            override fun serializer(): Serializer<Ident> =
                Serializer<Ident> { topic, data -> data.toString().toByteArray() }
            override fun deserializer(): Deserializer<Ident> =
                Deserializer<Ident> { topic, data -> Ident.validateOrThrow(data.decodeToString(), Ident.HistoriskStatus.UKJENT) }
        }
        val oppfolgingsperiodeStartetSerde = jsonSerde<OppfolgingsperiodeStartet>()
    }

    private val log = LoggerFactory.getLogger(this::class.java)
    fun process(
            record: Record<Ident, OppfolgingsperiodeStartet>
    ): RecordProcessingResult<String, String> {
        try {
            return runBlocking {
                val oppfolgingsperiode = record.value()
                return@runBlocking automatiskKontorRutingService
                    .tilordneKontorAutomatisk(oppfolgingsperiode)
                    .let { tilordningResultat ->
                        when (tilordningResultat) {
                            is TilordningRetry -> {
                                val melding = "Kunne ikke tilordne kontor ved start på oppfølgingsperiode: ${tilordningResultat.message}"
                                log.info(melding)
                                Retry(melding)
                            }
                            is TilordningFeil -> {
                                if (skipPersonIkkeFunnet && tilordningResultat.message.contains("Ingen foedselsdato i felt 'foedselsdato' fra pdl-spørring, dårlig data i dev?")) {
                                    log.info("Fant ikke alder på person i dev - hopper over melding")
                                    Skip()
                                } else {
                                    val melding = "Kunne ikke tilordne kontor ved start på oppfølgingsperiode: ${tilordningResultat.message}"
                                    log.error(melding)
                                    Retry(melding)
                                }
                            }
                            is TilordningSuccess -> {
                                when (tilordningResultat) {
                                    TilordningSuccessIngenEndring -> {
                                        log.info("Behandlet start oppfølging uten at noen kontor ble endret")
                                    }
                                    is TilordningSuccessKontorEndret -> {
                                        val arenaKontor = tilordningResultat.kontorEndretEvent.arenaKontorEndret
                                        val aoKontor = tilordningResultat.kontorEndretEvent.aoKontorEndret
                                        if (arenaKontor?.tilordning?.kontorId != aoKontor?.tilordning?.kontorId) {
                                            log.warn("Behandlet start oppfølging men fikk forskjelling kontor i arena og ao, arena: ${arenaKontor?.tilordning?.kontorId} - ao: ${aoKontor?.tilordning?.kontorId}")
                                        } else {
                                            log.info("Behandlet start oppfølging og fikk samme kontor i arena og ao: ${arenaKontor?.tilordning?.kontorId}")
                                        }
                                    }
                                }
                                Commit()
                            }
                        }
                    }
            }
        } catch (e: Throwable) {
            val feilmelding = "Klarte ikke behandle oppfolgingsperiode melding: ${e.message}"
            log.error(feilmelding, e)
            return Retry(feilmelding)
        }
    }
}
