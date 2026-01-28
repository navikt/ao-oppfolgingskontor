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

    val log = LoggerFactory.getLogger(this::class.java)


    fun runDaily2990AoKontorJob(database: Database) {
        val lockConfig = LockConfiguration(
            ZonedDateTime.now().toInstant(),
            "antall_2990_ao_kontor_i_dag",
            Duration.ofMinutes(60),
            Duration.ofMinutes(5)
        )
        val maybeLock = lockProvider.lock(lockConfig)
        if (maybeLock.isPresent) {
            val lock = maybeLock.get()
            try {
                val antall2990AoKontor = transaction(database) {
                    exec(
                        """
                    SELECT COUNT(*) AS ao_2990_count
                    FROM (
                             SELECT DISTINCT ON (fnr)
                                 fnr,
                                 kontor_id
                             FROM alternativ_aokontor
                             ORDER BY fnr, created_at DESC
                         ) siste_per_person
                    WHERE kontor_id = '2990';
                    """.trimIndent()
                    ) { rs ->
                        if (rs.next()) rs.getLong("ao_2990_count") else 0L
                    } ?: 0L
                }

                val row = mapOf(
                    "jobb_timestamp" to ZonedDateTime.now().toInstant().toString(),
                    "dato" to ZonedDateTime.now().toLocalDate().toString(),
                    "antall_2990_ao_kontor" to antall2990AoKontor
                )

                val tableId = TableId.of(DATASET_NAME, "antall_2990_ao_kontor_i_dag")
                val insertRequest = InsertAllRequest.newBuilder(tableId)
                    .addRow(row)
                    .build()

                val response = bigQuery.insertAll(insertRequest)

                if (response.hasErrors()) {
                    log.error("Feil ved innsending til BigQuery: ${response.insertErrors}")
                } else {
                    log.info("BigQuery OK – antall AO 2990: $antall2990AoKontor")
                }

            } finally {
                lock.unlock()
            }
        } else {
            log.info("Jobben hoppet over – en annen pod har allerede lås")
        }
    }
}
