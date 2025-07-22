package no.nav.kafka.consumers

import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import no.nav.db.Ident
import no.nav.domain.externalEvents.OppfolgingsperiodeAvsluttet
import no.nav.domain.externalEvents.OppfolgingsperiodeEndret
import no.nav.domain.externalEvents.OppfolgingsperiodeStartet
import no.nav.http.client.FnrFunnet
import no.nav.http.client.FnrIkkeFunnet
import no.nav.http.client.FnrOppslagFeil
import no.nav.http.client.FnrResult
import no.nav.kafka.consumers.OppfolgingsPeriodeConsumer.StartetBegrunnelse
import no.nav.kafka.processor.Commit
import no.nav.kafka.processor.RecordProcessingResult
import no.nav.kafka.processor.Retry
import no.nav.kafka.processor.Skip
import no.nav.services.AutomatiskKontorRutingService
import no.nav.services.OppfolgingsperiodeService
import no.nav.services.TilordningFeil
import no.nav.services.TilordningSuccess
import no.nav.utils.ZonedDateTimeSerializer
import org.apache.kafka.streams.processor.api.Record
import org.slf4j.LoggerFactory
import java.util.UUID

class OppfolgingsPeriodeConsumer(
        private val automatiskKontorRutingService: AutomatiskKontorRutingService,
        private val oppfolgingsperiodeService: OppfolgingsperiodeService,
        private val fnrProvider: suspend (aktorId: String) -> FnrResult
) {
    val log = LoggerFactory.getLogger(this::class.java)
    fun consume(
            record: Record<String, String>
    ): RecordProcessingResult<String, String> {
        val aktorId = record.key()
        try {
            return runBlocking {
                val ident: Ident = when (val result = fnrProvider(aktorId)) {
                    is FnrFunnet -> result.fnr
                    is FnrIkkeFunnet -> return@runBlocking Retry("Kunne ikke behandle oppfolgingsperiode melding: ${result.message}")
                    is FnrOppslagFeil -> return@runBlocking Retry("Kunne ikke behandle oppfolgingsperiode melding: ${result.message}")
                }

                val oppfolgingsperiode = Json
                    .decodeFromString<OppfolgingsperiodeDTO>(record.value())
                    .toOppfolgingsperiodeEndret(ident)

                when (oppfolgingsperiode) {
                    is OppfolgingsperiodeAvsluttet -> oppfolgingsperiodeService.deleteOppfolgingsperiode(ident)
                    is OppfolgingsperiodeStartet -> oppfolgingsperiodeService.saveOppfolgingsperiode(
                        ident,
                        oppfolgingsperiode.startDato,
                        oppfolgingsperiode.oppfolgingsperiodeId)
                }

                return@runBlocking automatiskKontorRutingService
                    .tilordneKontorAutomatisk(oppfolgingsperiode)
                    .let { tilordningResultat ->
                        when (tilordningResultat) {
                            is TilordningFeil -> {
                                log.error(tilordningResultat.message)
                                Retry("Kunne ikke tilordne kontor ved start på oppfølgingspeiode: ${tilordningResultat.message}")
                            }
                            is TilordningSuccess -> Commit()
                        }
                    }
            }
        } catch (e: Exception) {
            log.error("Klarte ikke behandle oppfolgingsperiode melding", e)
            return Skip()
        }
    }

    enum class StartetBegrunnelse {
        ARBEIDSSOKER,
        SYKEMELDT_MER_OPPFOLGING,
        MANUELL_REGISTRERING_VEILEDER // Ikke brukt enda
    }
}

@Serializable
data class OppfolgingsperiodeDTO(
        val uuid: String,
        @Serializable(with = ZonedDateTimeSerializer::class) val startDato: ZonedDateTime,
        @Serializable(with = ZonedDateTimeSerializer::class) val sluttDato: ZonedDateTime?,
        val aktorId: String,
        val startetBegrunnelse: StartetBegrunnelse
)

fun OppfolgingsperiodeDTO.toOppfolgingsperiodeEndret(fnr: Ident): OppfolgingsperiodeEndret {
    if (this.sluttDato == null) return OppfolgingsperiodeStartet(
        fnr,
        this.startDato,
        UUID.fromString(this.uuid)
    )
    return OppfolgingsperiodeAvsluttet(fnr)
}
