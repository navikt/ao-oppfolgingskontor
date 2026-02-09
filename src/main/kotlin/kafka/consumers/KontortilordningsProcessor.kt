package no.nav.kafka.consumers

import kafka.consumers.jsonSerde
import kafka.producers.OppfolgingEndretTilordningMelding
import kafka.producers.toKontorTilordningMelding
import kotlinx.coroutines.runBlocking
import no.nav.db.Ident
import no.nav.domain.OppfolgingsperiodeId
import no.nav.domain.externalEvents.OppfolgingsperiodeEndret
import no.nav.domain.externalEvents.OppfolgingsperiodeStartet
import no.nav.kafka.processor.Commit
import no.nav.kafka.processor.Forward
import no.nav.kafka.processor.RecordProcessingResult
import no.nav.kafka.processor.Retry
import no.nav.kafka.processor.Skip
import no.nav.services.AutomatiskKontorRutingService
import no.nav.services.KontorTilordningService
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
import java.time.ZonedDateTime

class KontortilordningsProcessor(
    private val automatiskKontorRutingService: AutomatiskKontorRutingService,
    private val skipPersonIkkeFunnet: Boolean = false,
    private val brukAoRuting: Boolean,
) {
    companion object {
        const val processorName = "KontortilordningsProcessor"

        val identSerde: Serde<Ident> = object : Serde<Ident> {
            override fun serializer(): Serializer<Ident> =
                Serializer<Ident> { topic, data -> data.toString().toByteArray() }
            override fun deserializer(): Deserializer<Ident> =
                Deserializer<Ident> { topic, data -> Ident.validateOrThrow(data.decodeToString(), Ident.HistoriskStatus.UKJENT) }
        }
        val oppfolgingsperiodeStartetSerde = jsonSerde<OppfolgingsperiodeEndret>()
    }

    private val log = LoggerFactory.getLogger(this::class.java)
    fun process(
            record: Record<Ident, OppfolgingsperiodeEndret>
    ): RecordProcessingResult<OppfolgingsperiodeId, OppfolgingEndretTilordningMelding> {
        try {
            val periodeStartet = record.value() as? OppfolgingsperiodeStartet ?: return Skip()

            return runBlocking {
                return@runBlocking automatiskKontorRutingService
                    .tilordneKontorAutomatiskVedStartOppfolging(periodeStartet)
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
                                        KontorTilordningService.tilordneKontor(tilordningResultat.kontorEndretEvent, brukAoRuting)
                                        val aoKontor = tilordningResultat.kontorEndretEvent.aoKontorEndret
                                        if (aoKontor != null)
                                            /* Publishing it done in its own processor to avoid re-setting kontor and creating
                                            extra-history if the publishing to kafka part fails. Forwarding without topicname
                                             send the record to default next step which is configured in the topology */
                                            return@let Forward(
                                                Record(
                                                    aoKontor.tilordning.oppfolgingsperiodeId,
                                                    /* Records need to be serializable and don't want to slap @Serializable
                                                    on domain-classes if it can be avoided */
                                                    aoKontor.toKontorTilordningMelding(),
                                                    ZonedDateTime.now().toEpochSecond()
                                                )
                                            )
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
