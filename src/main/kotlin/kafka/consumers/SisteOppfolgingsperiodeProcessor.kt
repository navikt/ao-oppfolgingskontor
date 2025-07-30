package kafka.consumers

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import no.nav.db.Ident
import no.nav.domain.OppfolgingsperiodeId
import no.nav.domain.externalEvents.OppfolgingsperiodeAvsluttet
import no.nav.domain.externalEvents.OppfolgingsperiodeEndret
import no.nav.domain.externalEvents.OppfolgingsperiodeStartet
import no.nav.http.client.FnrFunnet
import no.nav.http.client.FnrIkkeFunnet
import no.nav.http.client.FnrOppslagFeil
import no.nav.http.client.FnrResult
import no.nav.kafka.processor.Forward
import no.nav.kafka.processor.RecordProcessingResult
import no.nav.kafka.processor.Retry
import no.nav.kafka.processor.Skip
import no.nav.services.AktivOppfolgingsperiode
import no.nav.services.OppfolgingsperiodeService
import no.nav.utils.ZonedDateTimeSerializer
import org.apache.kafka.common.serialization.Deserializer
import org.apache.kafka.common.serialization.Serde
import org.apache.kafka.common.serialization.Serializer
import org.apache.kafka.streams.processor.api.Record
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZonedDateTime
import java.util.UUID

class SisteOppfolgingsperiodeProcessor(
    private val oppfolgingsperiodeService: OppfolgingsperiodeService,
    private val skipPersonIkkeFunnet: Boolean = false,
    private val fnrProvider: suspend (aktorId: String) -> FnrResult,
) {
    private val log = LoggerFactory.getLogger(SisteOppfolgingsperiodeProcessor::class.java)

    fun process(record: Record<String, String>): RecordProcessingResult<Ident, OppfolgingsperiodeStartet> {
        val aktorId = record.key()
        try {
             return runBlocking {
                val ident: Ident = when (val result = fnrProvider(aktorId)) {
                    is FnrFunnet -> result.ident
                    is FnrIkkeFunnet -> return@runBlocking Retry("Kunne ikke behandle oppfolgingsperiode melding: ${result.message}")
                    is FnrOppslagFeil -> {
                        if (skipPersonIkkeFunnet) {
                            if (result.message == "Fant ikke person: not_found") {
                                log.info("Fant ikke person i dev - hopper over melding")
                                return@runBlocking Skip()
                            } else {
                                return@runBlocking Retry("Kunne ikke behandle oppfolgingsperiode melding: ${result.message}")
                            }
                        } else {
                            return@runBlocking Retry("Kunne ikke behandle oppfolgingsperiode melding: ${result.message}")
                        }
                    }
                }

                val oppfolgingsperiode = Json
                    .decodeFromString<OppfolgingsperiodeDTO>(record.value())
                    .toOppfolgingsperiodeEndret(ident)

                when (oppfolgingsperiode) {
                    is OppfolgingsperiodeAvsluttet -> {
                        val currentOppfolgingsperiode = getCurrentPeriode(ident)
                        when (currentOppfolgingsperiode != null) {
                            true -> {
                                if (currentOppfolgingsperiode.startDato.isBefore(oppfolgingsperiode.startDato.toOffsetDateTime())) {
                                    oppfolgingsperiodeService.deleteOppfolgingsperiode(currentOppfolgingsperiode.periodeId)
                                }
                            }
                            false -> {}
                        }
                        Skip()
                    }
                    is OppfolgingsperiodeStartet -> {
                        if (oppfolgingsperiodeService.harNyerePeriodePåIdent(oppfolgingsperiode)) {
                            log.warn("Hadde nyere periode på ident, hopper over melding")
                            return@runBlocking Skip()
                        }
                        oppfolgingsperiodeService.saveOppfolgingsperiode(
                            ident,
                            oppfolgingsperiode.startDato,
                            oppfolgingsperiode.oppfolgingsperiodeId)
                        Forward(Record(
                            ident,
                            oppfolgingsperiode,
                            Instant.now().toEpochMilli(),
                        ), "asdas")
                    }
                }
            }
        } catch (e: Throwable) {
            val feilmelding = "Klarte ikke behandle oppfolgingsperiode melding: ${e.message}"
            log.error(feilmelding, e)
            return Retry(feilmelding)
        }
    }

    private fun getCurrentPeriode(ident: Ident): AktivOppfolgingsperiode? {
        val currentOppfolgingsperiodeResult = oppfolgingsperiodeService.getCurrentOppfolgingsperiode(ident)
        return when (currentOppfolgingsperiodeResult) {
            is AktivOppfolgingsperiode -> currentOppfolgingsperiodeResult
            else -> null
        }
    }
}


@Serializable
data class OppfolgingsperiodeDTO(
    val uuid: String,
    @Serializable(with = ZonedDateTimeSerializer::class) val startDato: ZonedDateTime,
    @Serializable(with = ZonedDateTimeSerializer::class) val sluttDato: ZonedDateTime?,
    val aktorId: String,
//        val startetBegrunnelse: StartetBegrunnelse
)

//enum class StartetBegrunnelse {
//    ARBEIDSSOKER,
//    SYKEMELDT_MER_OPPFOLGING,
//    MANUELL_REGISTRERING_VEILEDER // Ikke brukt enda
//}

fun OppfolgingsperiodeDTO.toOppfolgingsperiodeEndret(fnr: Ident): OppfolgingsperiodeEndret {
    val id = OppfolgingsperiodeId(UUID.fromString(this.uuid))
    if (this.sluttDato == null) return OppfolgingsperiodeStartet(fnr, this.startDato, id)
    return OppfolgingsperiodeAvsluttet(fnr, this.startDato, id)
}

val OppfolgingsPeriodeStartetSerde = jsonSerde<OppfolgingsperiodeStartet>()