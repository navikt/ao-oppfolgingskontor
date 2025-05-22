package no.nav.kafka

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import no.nav.db.entity.ArenaKontorEntity
import no.nav.db.table.ArenaKontorTable
import no.nav.domain.KontorId
import no.nav.domain.KontorTilordning
import no.nav.domain.events.EndringPaaOppfolgingsBrukerFraArena
import no.nav.kafka.processor.RecordProcessingResult
import no.nav.services.KontorTilordningService
import org.apache.kafka.streams.processor.api.Record
import org.apache.kafka.streams.processor.api.RecordMetadata
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime

class EndringPaOppfolgingsBrukerConsumer() {
    val log = LoggerFactory.getLogger(EndringPaOppfolgingsBrukerConsumer::class.java)

    val json = Json { ignoreUnknownKeys = true }

    fun consume(record: Record<String, String>, maybeRecordMetadata: RecordMetadata?): RecordProcessingResult {
        log.info("Consumed record")
        val fnrString = record.key()
        val endringPaOppfolgingsBruker = json.decodeFromString<EndringPaOppfolgingsBruker>(record.value())
        if (endringPaOppfolgingsBruker.oppfolgingsenhet.isNullOrBlank()) {
            log.warn("Mottok endring på oppfølgingsbruker uten gyldig kontorId")
            return RecordProcessingResult.COMMIT
        }

        val sistEndretKontorEntity = transaction {
            ArenaKontorEntity
                .find { ArenaKontorTable.id eq fnrString }
                .firstOrNull()
        }

        if(sistEndretKontorEntity != null && sistEndretKontorEntity.sistEndretDatoArena > endringPaOppfolgingsBruker.sistEndretDato.convertToOffsetDatetime()) {
            log.warn("Sist endret kontor er eldre enn endring på oppfølgingsbruker")
            return RecordProcessingResult.SKIP
        }

        KontorTilordningService.settKontorTilhorighet(
            EndringPaaOppfolgingsBrukerFraArena(
                tilordning = KontorTilordning(
                    fnr = fnrString,
                    kontorId = KontorId(endringPaOppfolgingsBruker.oppfolgingsenhet),
                ),
                sistEndretDatoArena = endringPaOppfolgingsBruker.sistEndretDato.convertToOffsetDatetime(),
                offset = maybeRecordMetadata!!.offset(),
                partition = maybeRecordMetadata.partition(),
            )
        )
        return RecordProcessingResult.COMMIT
    }
}

// ""sistEndretDato":string"2025-04-10T13:01:14+02:00"

fun String.convertToOffsetDatetime(): OffsetDateTime {
    return OffsetDateTime.parse(this)
}

@Serializable
data class EndringPaOppfolgingsBruker(
    val oppfolgingsenhet: String?,
    val sistEndretDato: String
)