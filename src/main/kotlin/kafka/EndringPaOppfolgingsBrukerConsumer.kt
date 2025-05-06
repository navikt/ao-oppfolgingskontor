package no.nav.kafka

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import no.nav.db.dto.EndretAvType
import no.nav.db.entity.ArenaKontorEntity
import no.nav.db.table.ArenaKontorTable
import no.nav.db.table.KontorhistorikkTable
import no.nav.kafka.processor.RecordProcessingResult
import org.apache.kafka.streams.processor.api.Record
import org.apache.kafka.streams.processor.api.RecordMetadata
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

    var counter = 0

    fun consume(record: Record<String, String>, maybeRecordMetadata: RecordMetadata?): RecordProcessingResult {
        log.info("Consuming record topic: {} partition: {}, offset: {}", maybeRecordMetadata?.topic(), maybeRecordMetadata?.partition(), maybeRecordMetadata?.offset())
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

        transaction {
            KontorhistorikkTable.insert {
                it[fnr] = fnrString
                it[kontorId] = endringPaOppfolgingsBruker.oppfolgingsenhet
            }

            ArenaKontorTable.upsert {
                it[id] = fnrString
                it[kontorId] = endringPaOppfolgingsBruker.oppfolgingsenhet
                it[updatedAt] = OffsetDateTime.now()
                it[sistEndretDatoArena] = endringPaOppfolgingsBruker.sistEndretDato.convertToOffsetDatetime()
                it[kafkaOffset] = maybeRecordMetadata?.offset()?.toInt()
                it[kafkaPartition] = maybeRecordMetadata?.partition()

            }
            val envVar: String = System.getenv("NAIS_CLUSTER_NAME") ?: "NONE"
            if (envVar == "dev-gcp" && counter++ < 5)  throw RuntimeException("Simulerer feil i oppdatering av kontorhistorikk")
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