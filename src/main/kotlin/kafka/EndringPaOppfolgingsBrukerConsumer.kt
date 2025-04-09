package no.nav.kafka

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import no.nav.db.dto.EndretAvType
import no.nav.db.table.ArenaKontorTable
import no.nav.kafka.processor.RecordProcessingResult
import org.slf4j.LoggerFactory
import org.apache.kafka.streams.processor.api.Record
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert

class EndringPaOppfolgingsBrukerConsumer(
//    val dataSource: DataSource,
) {
    val log = LoggerFactory.getLogger(EndringPaOppfolgingsBrukerConsumer::class.java)

    val json = Json { ignoreUnknownKeys = true }

    fun consume(record: Record<String, String>): RecordProcessingResult {
        log.info("Consumed record")
        val fnrString = record.key()
        val endringPaOppfolgingsBruker = json.decodeFromString<EndringPaOppfolgingsBruker>(record.value())
        if (endringPaOppfolgingsBruker.oppfolgingsenhet.isNullOrBlank()) {
            log.warn("Mottok endring på oppfølgingsbruker uten gyldig kontorId")
            return RecordProcessingResult.COMMIT
        }

        transaction {
            ArenaKontorTable.upsert {
                it[id] = fnrString
                it[kontorId] = endringPaOppfolgingsBruker.oppfolgingsenhet
                it[endretAv] = "ukjent"
                it[endretAvType] = EndretAvType.ARENA.name
            }
        }
        return RecordProcessingResult.COMMIT
    }
}

@Serializable
data class EndringPaOppfolgingsBruker(
    val oppfolgingsenhet: String?
)