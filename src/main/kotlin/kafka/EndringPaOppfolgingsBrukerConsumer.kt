package no.nav.kafka

import kotlinx.serialization.json.Json
import no.nav.db.ArenaOppfolgingsKontorTable
import no.nav.db.dto.EndretAvType
import no.nav.kafka.processor.RecordProcessingResult
import org.slf4j.LoggerFactory
import org.apache.kafka.streams.processor.api.Record
import org.jetbrains.exposed.sql.insert
import javax.sql.DataSource

class EndringPaOppfolgingsBrukerConsumer(
    val dataSource: DataSource,
) {
    val log = LoggerFactory.getLogger(EndringPaOppfolgingsBrukerConsumer::class.java)

    fun consume(record: Record<String, String>): RecordProcessingResult {
        log.info("Consumed record")
        val fnrString = record.value()
        val endringPaOppfolgingsBruker = Json.decodeFromString<EndringPaOppfolgingsBruker>(record.value())
        if (endringPaOppfolgingsBruker.oppfolgingsenhet.isNullOrBlank()) {
            log.warn("Mottok endring på oppfølgingsbruker uten gyldig kontorId")
            return RecordProcessingResult.COMMIT
        }
        ArenaOppfolgingsKontorTable.insert {
            it[fnr] = fnrString
            it[kontorId] = endringPaOppfolgingsBruker.oppfolgingsenhet
            it[endretAv] = "ukjent"
            it[endretAvType] = EndretAvType.ARENA.name
        }
        return RecordProcessingResult.COMMIT
    }
}

data class EndringPaOppfolgingsBruker(
    val oppfolgingsenhet: String?
)