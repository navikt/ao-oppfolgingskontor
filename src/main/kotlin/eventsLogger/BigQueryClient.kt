package eventsLogger

import com.google.cloud.bigquery.BigQueryOptions
import com.google.cloud.bigquery.InsertAllRequest
import com.google.cloud.bigquery.TableId
import net.javacrumbs.shedlock.core.LockConfiguration
import net.javacrumbs.shedlock.provider.exposed.ExposedLockProvider
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.ZonedDateTime

class BigQueryClient(
    projectId: String,
    private val lockProvider: ExposedLockProvider
) {

    private val DATASET_NAME = "kontor_metrikker"
    private val TABLE_NAME = "antall_2990_kontor"
    private val bigQuery = BigQueryOptions.newBuilder().setProjectId(projectId).build().service
    private val log = LoggerFactory.getLogger(this::class.java)

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

                val tableId = TableId.of(DATASET_NAME, TABLE_NAME)
                val insertRequest = InsertAllRequest.newBuilder(tableId)
                    .addRow(row)
                    .build()

                val response = bigQuery.insertAll(insertRequest)

                if (response.hasErrors()) {
                    log.error("Feil ved innsending til BigQuery: ${response.insertErrors}")
                } else {
                    log.info("BigQuery OK – antall 2990 per kontor: $row")
                }
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
