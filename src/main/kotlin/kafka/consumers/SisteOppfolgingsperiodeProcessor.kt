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
import no.nav.utils.ZonedDateTimeSerializer
import org.apache.kafka.streams.processor.api.Record
import org.slf4j.LoggerFactory
import services.HaddeNyerePeriodePåIdent
import services.HaddePeriodeAllerede
import services.HarSlettetPeriode
import services.OppfolgingsperiodeService
import services.OppfølgingsperiodeLagret
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
                            oppfolgingsperiodeService.behandleOppfolgingsperiodeAvsluttetIdentNotFound(oppfolgingsperiodeDto)
                            return@runBlocking Commit()
                        }
                        return@runBlocking Retry("Kunne ikke behandle oppfolgingsperiode melding: ${result.message}")
                    }
                }

                val oppfolgingsperiode = oppfolgingsperiodeDto.toOppfolgingsperiodeEndret(ident)

                when (oppfolgingsperiode) {
                    is OppfolgingsperiodeAvsluttet -> {
                        oppfolgingsperiodeService.handterPeriodeAvsluttet(oppfolgingsperiode)
                        log.info("melding på sisteoppfolgingsperiode (avsluttet) ferdig prosessert")
                        Commit()
                    }
                    is OppfolgingsperiodeStartet -> {
                        val result = oppfolgingsperiodeService.handterPeriodeStartet(oppfolgingsperiode)
                        log.info("melding på sisteoppfolgingsperiode (startet) ferdig prosessert")
                        when (result) {
                            HaddeNyerePeriodePåIdent, HaddePeriodeAllerede, HarSlettetPeriode -> Skip()
                            OppfølgingsperiodeLagret -> Forward(
                                Record(
                                ident,
                                oppfolgingsperiode,
                                Instant.now().toEpochMilli(),
                            ), null)
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
    if (this.sluttDato == null) return OppfolgingsperiodeStartet(fnr, this.startDato, id, null)
    return OppfolgingsperiodeAvsluttet(fnr, this.startDato, id)
}
