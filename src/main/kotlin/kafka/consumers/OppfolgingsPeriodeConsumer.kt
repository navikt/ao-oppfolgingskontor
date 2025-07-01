package no.nav.kafka.consumers

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import no.nav.kafka.processor.Commit
import no.nav.kafka.processor.RecordProcessingResult
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
            if (oppfolgingsperiode.sluttDato != null) {
                return Skip
            } else {
                return runBlocking {
                    val resultat = automatiskKontorRutingService.tilordneKontorAutomatisk(aktorId)
                    return@runBlocking when (resultat) {
                        is TilordningFeil -> {
                            log.error(resultat.message)
                            Skip
                        }
                        is TilordningSuccess -> Commit
                    }
                }
            }
        } catch (e: Exception) {
            log.error("Klarte ikke behandle oppfolgingsperiode melding", e)
            // Log the error and skip processing this record
            return Skip
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

    enum class StartetBegrunnelse {
        ARBEIDSSOKER,
        SYKEMELDT_MER_OPPFOLGING,
        MANUELL_REGISTRERING_VEILEDER // Ikke brukt enda
    }
}