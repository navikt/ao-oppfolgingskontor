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
    private val bigQuery = BigQueryOptions.newBuilder().setProjectId(projectId).build().service
    private val log = LoggerFactory.getLogger(this::class.java)

    /**
     * Generisk funksjon for å kjøre daglige BigQuery-jobber.
     *
     * @param database Exposed Database
     * @param dbTabell Navn på tabellen i databasen
     * @param bigQueryTabell Navn på tabellen i BigQuery
     * @param sqlBuilder Lambda som returnerer SQL-spørring for å hente antall
     */
    private fun runDailyBigQueryJob(
        database: Database,
        dbTabell: String,
        bigQueryTabell: String,
        sqlBuilder: (String) -> String
    ) {
        val lockConfig = LockConfiguration(
            ZonedDateTime.now().toInstant(),
            "bigquery_job_$bigQueryTabell",
            Duration.ofMinutes(60),
            Duration.ofMinutes(5)
        )

        val maybeLock = lockProvider.lock(lockConfig)
        if (maybeLock.isPresent) {
            val lock = maybeLock.get()
            try {
                val antall = transaction(database) {
                    exec(sqlBuilder(dbTabell)) { rs ->
                        if (rs.next()) rs.getLong("ao_2990_count") else 0L
                    } ?: 0L
                }

                val row = mapOf(
                    "jobb_timestamp" to ZonedDateTime.now().toInstant().toString(),
                    "dato" to ZonedDateTime.now().toLocalDate().toString(),
                    "antall_2990_ao_kontor" to antall
                )

                val tableId = TableId.of(DATASET_NAME, bigQueryTabell)
                val insertRequest = InsertAllRequest.newBuilder(tableId)
                    .addRow(row)
                    .build()

                val response = bigQuery.insertAll(insertRequest)

                if (response.hasErrors()) {
                    log.error("Feil ved innsending til BigQuery ($bigQueryTabell): ${response.insertErrors}")
                } else {
                    log.info("BigQuery OK – $bigQueryTabell: $antall")
                }
            } finally {
                lock.unlock()
            }
        } else {
            log.info("Jobben $bigQueryTabell hoppet over – en annen pod har allerede lås")
        }
    }

    // === Spesifikke jobber ===

    fun antallAlternativAoKontorSomEr2990(database: Database) {
        runDailyBigQueryJob(database, "alternativ_aokontor", "antall_alternativ_ao_kontor_2990") { table ->
            """
            SELECT COUNT(*) AS ao_2990_count
            FROM (
                SELECT DISTINCT ON (fnr) fnr, kontor_id
                FROM $table
                ORDER BY fnr, created_at DESC
            ) siste_per_person
            WHERE kontor_id = '2990';
            """.trimIndent()
        }
    }

    fun antallArbeidsoppfolgingskontorSomEr2990(database: Database) {
        runDailyBigQueryJob(database, "arbeidsoppfolgingskontor", "antall_arbeidsoppfolgingskontor_2990") { table ->
            """
            SELECT COUNT(*) AS ao_2990_count
            FROM $table
            WHERE kontor_id = '2990';
            """.trimIndent()
        }
    }
}
