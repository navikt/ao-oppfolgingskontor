package no.nav.kafka.consumers

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import no.nav.kafka.processor.RecordProcessingResult
import no.nav.services.AutomatiskKontorRutingService
import org.apache.kafka.streams.processor.api.Record
import org.apache.kafka.streams.processor.api.RecordMetadata
import java.time.ZonedDateTime

class OppfolgingsPeriodeConsumer(
    private val automatiskKontorRutingService: AutomatiskKontorRutingService
) {
    fun consume(record: Record<String, String>, maybeRecordMetadata: RecordMetadata?): RecordProcessingResult {
        val aktorId = record.key()
        val oppfolgingsperiode = Json.decodeFromString<OppfolgingsperiodeDTO>(record.value())

        if (oppfolgingsperiode.sluttDato != null) {
            return RecordProcessingResult.SKIP
        } else {
            automatiskKontorRutingService.tilordneKontorAutomatisk(aktorId)
            return RecordProcessingResult.COMMIT
        }
    }

    @Serializable
    data class OppfolgingsperiodeDTO(
        val uuid: String,
        val startDato: ZonedDateTime,
        val sluttDato: ZonedDateTime?,
        val aktorId: String
    )
}