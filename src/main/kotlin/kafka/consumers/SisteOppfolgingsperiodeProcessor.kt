package kafka.consumers

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import no.nav.db.AktorId
import no.nav.db.Ident
import no.nav.domain.OppfolgingsperiodeId
import no.nav.domain.externalEvents.OppfolgingsperiodeAvsluttet
import no.nav.domain.externalEvents.OppfolgingsperiodeEndret
import no.nav.domain.externalEvents.OppfolgingsperiodeStartet
import no.nav.http.client.IdentFunnet
import no.nav.http.client.IdentIkkeFunnet
import no.nav.http.client.IdentOppslagFeil
import no.nav.http.client.IdentResult
import no.nav.kafka.processor.Commit
import no.nav.kafka.processor.Forward
import no.nav.kafka.processor.RecordProcessingResult
import no.nav.kafka.processor.Retry
import no.nav.kafka.processor.Skip
import no.nav.services.AktivOppfolgingsperiode
import no.nav.services.OppfolgingsperiodeService
import no.nav.utils.ZonedDateTimeSerializer
import org.apache.kafka.streams.processor.api.Record
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZonedDateTime
import java.util.UUID

class SisteOppfolgingsperiodeProcessor(
    private val oppfolgingsperiodeService: OppfolgingsperiodeService,
    private val skipPersonIkkeFunnet: Boolean = false,
    private val fnrProvider: suspend (aktorId: AktorId) -> IdentResult,
) {
    private val log = LoggerFactory.getLogger(SisteOppfolgingsperiodeProcessor::class.java)

    fun process(record: Record<String, String>): RecordProcessingResult<Ident, OppfolgingsperiodeStartet> {
        try {
            val aktorId = AktorId(record.key())
            return runBlocking {
                val oppfolgingsperiodeDto = Json
                    .decodeFromString<OppfolgingsperiodeDTO>(record.value())

                val ident: Ident = when (val result = fnrProvider(aktorId)) {
                    is IdentFunnet -> result.ident
                    is IdentIkkeFunnet -> return@runBlocking Retry("Kunne ikke behandle oppfolgingsperiode melding: ${result.message}")
                    is IdentOppslagFeil -> {
                        if (skipPersonIkkeFunnet && result.message == "Fant ikke person: not_found") {
                            log.info("Fant ikke person i dev - hopper over melding")
                            return@runBlocking Skip()
                        }

                        if (oppfolgingsperiodeDto.sluttDato != null) {
                            log.warn("Fant ikke person i PDL, men behandler melding likevel fordi oppfolgingsperioden uansett er avsluttet")
                            return@runBlocking behandleOppfolgingsperiodeAvsluttetIdentNotFound(oppfolgingsperiodeDto)
                        }

                        return@runBlocking Retry("Kunne ikke behandle oppfolgingsperiode melding: ${result.message}")
                    }
                }

                val oppfolgingsperiode = oppfolgingsperiodeDto
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
                        oppfolgingsperiodeService.deleteOppfolgingsperiode(oppfolgingsperiode.periodeId)
                        log.info("melding på sisteoppfolgingsperiode (avsluttet) ferdig prosessert")
                        Commit()
                    }
                    is OppfolgingsperiodeStartet -> {
                        if (oppfolgingsperiodeService.harNyerePeriodePåIdent(oppfolgingsperiode)) {
                            log.warn("Hadde nyere periode på ident, hopper over melding")
                            return@runBlocking Skip()
                        }
                        oppfolgingsperiodeService.saveOppfolgingsperiode(
                            ident,
                            oppfolgingsperiode.startDato,
                            oppfolgingsperiode.periodeId)
                        log.info("melding på sisteoppfolgingsperiode (startet) ferdig prosessert")
                        Forward(Record(
                            ident,
                            oppfolgingsperiode,
                            Instant.now().toEpochMilli(),
                        ), null)
                    }
                }
            }
        } catch (e: Throwable) {
            val feilmelding = "Klarte ikke behandle oppfolgingsperiode melding: ${e.message}"
            log.error(feilmelding, e)
            return Retry(feilmelding)
        }
    }

    private fun behandleOppfolgingsperiodeAvsluttetIdentNotFound(
        oppfolgingsperiodeDto: OppfolgingsperiodeDTO,
    ): RecordProcessingResult<Ident, OppfolgingsperiodeStartet> {
        val oppfolgingsperiodeId = OppfolgingsperiodeId(UUID.fromString(oppfolgingsperiodeDto.uuid))
        oppfolgingsperiodeService.deleteOppfolgingsperiode(oppfolgingsperiodeId)
        return Commit()
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