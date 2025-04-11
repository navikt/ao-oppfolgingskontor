package no.nav.kafka

import kotlinx.datetime.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import no.nav.db.dto.EndretAvType
import no.nav.db.entity.SistEndretKontorEntity
import no.nav.db.table.ArenaKontorTable
import no.nav.db.table.KontorhistorikkTable
import no.nav.db.table.KontorhistorikkTable.fnr
import no.nav.kafka.processor.RecordProcessingResult
import org.apache.kafka.streams.processor.api.Record
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime

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

        val sistEndretKontorEntity = transaction {
            SistEndretKontorEntity
                .find { fnr eq fnr }
                .maxByOrNull { it.createdAt }
        }

        if(sistEndretKontorEntity != null && sistEndretKontorEntity.createdAt > endringPaOppfolgingsBruker.sistEndretDato.convertToOffsetDatetime()) {
            log.warn("Sist endret kontor er eldre enn endring på oppfølgingsbruker")
            return RecordProcessingResult.SKIP
        }

        transaction {
            KontorhistorikkTable.insert {
                it[fnr] = fnrString
                it[kontorId] = endringPaOppfolgingsBruker.oppfolgingsenhet
            }

            ArenaKontorTable.upsert {
                it[id] = fnrString
                it[kontorId] = endringPaOppfolgingsBruker.oppfolgingsenhet
                it[endretAv] = "ukjent"
                it[endretAvType] = EndretAvType.ARENA.name
                it[updatedAt] = OffsetDateTime.now()
            }
        }


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