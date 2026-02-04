package eventsLogger

import com.google.cloud.bigquery.BigQueryOptions
import com.google.cloud.bigquery.InsertAllRequest
import com.google.cloud.bigquery.TableId
import net.javacrumbs.shedlock.core.LockConfiguration
import net.javacrumbs.shedlock.provider.exposed.ExposedLockProvider
import no.nav.domain.KontorEndringsType
import no.nav.domain.KontorId
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.ZonedDateTime

enum class KontorTypeForBigQuery {
    ARBEIDSOPPFOLGINGSKONTOR,
    ALTERNATIV_AOKONTOR,
    ARENAKONTOR
}

class BigQueryClient(
    projectId: String,
    private val lockProvider: ExposedLockProvider
) {
    private val DATASET_NAME = "kontor_metrikker"
    private val TABLE_NAME = "antall_2990_kontor"
    private val KONTOR_EVENTS = "KONTOR_EVENTS"
    private val bigQuery = BigQueryOptions.newBuilder().setProjectId(projectId).build().service
    private val log = LoggerFactory.getLogger(this::class.java)
    val kontorEventsTable = TableId.of(DATASET_NAME, KONTOR_EVENTS)

    fun loggSattKontorEvent(kontorId: KontorId, kontorEndringsType: KontorEndringsType?, kontorType: KontorTypeForBigQuery) {
        insertIntoKontorEvents(kontorEventsTable) {
            mapOf(
                "kontorId" to kontorId.toString(),
                "timestamp" to ZonedDateTime.now().toOffsetDateTime().toString(),
                "kontorEndringsType" to kontorEndringsType.toString(),
                "kontorType" to kontorType.toString(),
            )
        }
    }

    private fun TableId.insertRequest(row: Map<String, Any>): InsertAllRequest {
        return InsertAllRequest.newBuilder(this).addRow(row).build()
    }

    private fun insertIntoKontorEvents(table: TableId, getRow: () -> Map<String, Any>?) {
        runCatching {
            val row = getRow()
            if (row == null) return
            val insertRequest = table.insertRequest(row)
            insertWhileToleratingErrors(insertRequest)
        }
            .onFailure { log.warn("Kunne ikke logge kontor event i bigquery", it) }
    }

    private fun insertWhileToleratingErrors(insertRequest: InsertAllRequest) {
        val response = bigQuery.insertAll(insertRequest)
        val errors = response.insertErrors
        if (errors.isNotEmpty()) {
            log.error("Error inserting bigquery rows: $errors")
        }
    }

    fun antall2990Kontor(database: Database) {
        val lockConfig = LockConfiguration(
            ZonedDateTime.now().toInstant(),
            "bigquery_job_all_kontor",
            Duration.ofMinutes(60),
            Duration.ofMinutes(5)
        )

        val maybeLock = lockProvider.lock(lockConfig)
        if (maybeLock.isPresent) {
            val lock = maybeLock.get()
            try {
                val antallAlternativAoKontor = hentAntall(database, "alternativ_aokontor")
                val antallArbeidsoppfolgingskontor = hentAntall(database, "arbeidsoppfolgingskontor")
                val antallArenaKontor = hentAntall(database, "arenakontor")

                val row = mapOf(
                    "jobb_timestamp" to ZonedDateTime.now().toInstant().toString(),
                    "dato" to ZonedDateTime.now().toLocalDate().toString(),
                    "alternativ_aokontor" to antallAlternativAoKontor,
                    "arenakontor" to antallArenaKontor,
                    "arbeidsoppfolgingskontor" to antallArbeidsoppfolgingskontor
                )

                val table = TableId.of(DATASET_NAME, TABLE_NAME)
                val insertRequest = table.insertRequest(row)
                insertWhileToleratingErrors(insertRequest)
            } finally {
                lock.unlock()
            }
        } else {
            log.info("Jobben hoppet over – en annen pod har allerede lås")
        }
    }

    private fun hentAntall(database: Database, tabell: String): Long {
        return transaction(database) {
            exec(
                when (tabell) {
                    "alternativ_aokontor" -> """
                        SELECT COUNT(*) AS antall
                        FROM (
                            SELECT DISTINCT ON (fnr) fnr, kontor_id
                            FROM alternativ_aokontor
                            ORDER BY fnr, created_at DESC
                        ) siste_per_person
                        WHERE kontor_id = '2990';
                    """.trimIndent()
                    "arbeidsoppfolgingskontor" -> """
                        SELECT COUNT(*) AS antall
                        FROM arbeidsoppfolgingskontor
                        WHERE kontor_id = '2990';
                    """.trimIndent()
                    "arenakontor" -> """
                        SELECT COUNT(*) AS antall
                        FROM arenakontor
                        WHERE kontor_id = '2990';
                    """.trimIndent()
                    else -> throw IllegalArgumentException("Ukjent tabell: $tabell")
                }
            ) { rs -> if (rs.next()) rs.getLong("antall") else 0L } ?: 0L
        }
    }
}
