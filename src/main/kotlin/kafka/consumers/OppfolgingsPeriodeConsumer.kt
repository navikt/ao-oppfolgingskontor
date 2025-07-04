package no.nav.kafka.consumers

import io.ktor.network.tls.CertificateAndKey
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import no.nav.domain.externalEvents.OppfolgingperiodeAvsluttet
import no.nav.domain.externalEvents.OppfolgingperiodeStartet
import no.nav.domain.externalEvents.OppfolgingsperiodeEndret
import no.nav.kafka.consumers.OppfolgingsPeriodeConsumer.StartetBegrunnelse
import no.nav.kafka.processor.Commit
import no.nav.kafka.processor.RecordProcessingResult
import no.nav.kafka.processor.Retry
import no.nav.kafka.processor.Skip
import no.nav.services.AutomatiskKontorRutingService
import no.nav.services.TilordningFeil
import no.nav.services.TilordningSuccess
import no.nav.utils.ZonedDateTimeSerializer
import org.apache.kafka.streams.processor.api.Record
import org.apache.kafka.streams.processor.api.RecordMetadata
import org.slf4j.LoggerFactory
import java.time.ZonedDateTime

class OppfolgingsPeriodeConsumer(
    private val automatiskKontorRutingService: AutomatiskKontorRutingService
) {
    val log = LoggerFactory.getLogger(this::class.java)
    fun consume(record: Record<String, String>, maybeRecordMetadata: RecordMetadata?): RecordProcessingResult<Unit, Unit> {
        val aktorId = record.key()
        try {
            val oppfolgingsperiode = Json.decodeFromString<OppfolgingsperiodeDTO>(record.value())
                .toOppfolgingsperiodeEndret(aktorId)
            return runBlocking {
                return@runBlocking automatiskKontorRutingService
                    .tilordneKontorAutomatisk(oppfolgingsperiode)
                    .let { tilordningResultat ->
                        when (tilordningResultat) {
                            is TilordningFeil -> {
                                log.error(tilordningResultat.message)
                                Retry("Kunne ikke tilordne kontor ved start på oppfølgingspeiode: ${tilordningResultat.message}")
                            }
                            is TilordningSuccess -> Commit
                        }
                    }
            }
        } catch (e: Exception) {
            log.error("Klarte ikke behandle oppfolgingsperiode melding", e)
            return Skip
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
    @Serializable(with = ZonedDateTimeSerializer::class)
    val startDato: ZonedDateTime,
    @Serializable(with = ZonedDateTimeSerializer::class)
    val sluttDato: ZonedDateTime?,
    val aktorId: String,
    val startetBegrunnelse: StartetBegrunnelse
)

fun OppfolgingsperiodeDTO.toOppfolgingsperiodeEndret(aktorId: String): OppfolgingsperiodeEndret {
    if (this.sluttDato == null) return OppfolgingperiodeStartet(aktorId)
    return OppfolgingperiodeAvsluttet(aktorId)
}