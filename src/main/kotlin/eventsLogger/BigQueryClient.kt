package eventsLogger

import com.google.cloud.bigquery.BigQueryOptions
import com.google.cloud.bigquery.InsertAllRequest
import com.google.cloud.bigquery.TableId
import net.javacrumbs.shedlock.core.LockConfiguration
import net.javacrumbs.shedlock.provider.exposed.ExposedLockProvider
import org.jetbrains.exposed.sql.statements.StatementType
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

class BigQueryClient(
    projectId: String,
    private val lockProvider: ExposedLockProvider
) {

    private val DATASET_NAME = "kontor_metrikker"
    private val bigQuery = BigQueryOptions.newBuilder().setProjectId(projectId).build().service

    val log = LoggerFactory.getLogger(this::class.java)

    fun sendAlle2990AvvikTilBigQuery() {
        val lockConfig = LockConfiguration(
            Instant.now(),
            "bigquery_avvik_2990_snapshot",
            Duration.ofMinutes(65),
            Duration.ofMinutes(3)
        )

        val maybeLock = lockProvider.lock(lockConfig)
        if (maybeLock.isPresent) {
            val acquiredLock = maybeLock.get()
            try {
                log.info("Starter BigQuery-jobb med lås")
                log.info("Henter avviksdata fra Postgres")

                val rows = hentAvvik2990AoKontorVsArenakontor()

                log.info("Fant ${rows.size} rader – laster sender til BigQuery")

                log.info("Tømmer eksisterende snapshot-tabell i BigQuery")
                truncateSnapshotTable()
                log.info("Sender rader til BigQuery")
                insert2990AvvikRows(rows)
                log.info("BigQuery-jobb ferdig")
            } finally {
                acquiredLock.unlock()
            }
        } else {
            log.info("BigQuery-jobben hoppet over – lås allerede tatt av en annen pod")
        }
    }

    private fun hentAvvik2990AoKontorVsArenakontor(): List<Map<String, Any?>> =
        transaction {
            val rows = mutableListOf<Map<String, Any?>>()

            exec(
                """
                        WITH gt_start AS
                          (SELECT ident,
                                  oppfolgingsperiode_id,
                                  created_at AS gt_start_tid
                           FROM kontorhistorikk
                           WHERE kontorendringstype = 'GTKontorVedOppfolgingStart'),
                           
                             arena_start AS
                          (SELECT ident,
                                  oppfolgingsperiode_id,
                                  MIN(kontor_id) AS arenakontor_start
                           FROM kontorhistorikk
                           WHERE kontorendringstype IN ('ArenaKontorHentetSynkrontVedOppfolgingsStart',
                                                        'ArenaKontorVedOppfolgingStartMedEtterslep')
                           GROUP BY ident,
                                    oppfolgingsperiode_id),
                                    
                             arena_endret AS
                          (SELECT ident,
                                  oppfolgingsperiode_id,
                                  kontor_id AS arenakontor_sist_endret_til,
                                  created_at AS arena_siste_endring
                           FROM
                             (SELECT *,
                                     ROW_NUMBER() OVER (PARTITION BY ident, oppfolgingsperiode_id
                                                        ORDER BY created_at DESC) AS rn
                              FROM kontorhistorikk
                              WHERE kontorendringstype = 'EndretIArena') t
                           WHERE rn = 1),
                           
                             arena_endringer AS
                          (SELECT ident,
                                  oppfolgingsperiode_id,
                                  COUNT(*) AS antall_arena_endringer
                           FROM kontorhistorikk
                           WHERE kontorendringstype = 'EndretIArena'
                           GROUP BY ident,
                                    oppfolgingsperiode_id)
                                    
                        SELECT 
                               --ROW_NUMBER() OVER (ORDER BY (ae.arena_siste_endring - gt.gt_start_tid) NULLS LAST) AS rekkefolge_tid,
                               --ao.fnr,
                               gt.oppfolgingsperiode_id,
                               --DENSE_RANK() OVER (ORDER BY ao.fnr) AS person,
                               CASE
                                   WHEN ae.arena_siste_endring IS NOT NULL THEN 'ENDRET'
                                   ELSE 'IKKE_ENDRET'
                               END AS arenakontor_endret,
                               ao.kontor_id AS aokontor,
                               ar.arenakontor_start,
                               ae.arenakontor_sist_endret_til,
                               COALESCE(ae2.antall_arena_endringer,0) AS antall_arena_endringer,
                               (ae.arena_siste_endring - gt.gt_start_tid) AS tid_diff,
                               EXTRACT(EPOCH FROM (ae.arena_siste_endring - gt.gt_start_tid))::bigint AS tid_diff_sek,
                               ao.created_at AS oppfolging_startet_tidspunkt,
                               ae.arena_siste_endring AS arena_siste_endret_tidspunkt
                        FROM alternativ_aokontor ao
                        JOIN gt_start gt ON gt.ident = ao.fnr
                        AND gt.gt_start_tid = ao.created_at
                        LEFT JOIN arena_start ar ON ar.ident = gt.ident
                        AND ar.oppfolgingsperiode_id = gt.oppfolgingsperiode_id
                        LEFT JOIN arena_endret ae ON ae.ident = gt.ident
                        AND ae.oppfolgingsperiode_id = gt.oppfolgingsperiode_id
                        LEFT JOIN arena_endringer ae2 ON ae2.ident = gt.ident
                        AND ae2.oppfolgingsperiode_id = gt.oppfolgingsperiode_id
                        WHERE ao.kontor_id = '2990'
                          AND (ar.arenakontor_start IS NULL
                               OR ar.arenakontor_start <> '2990')
            """.trimIndent(),
                explicitStatementType = StatementType.SELECT
            ) { rs ->

                while (rs.next()) {
                    rows.add(
                        mapOf(
                            "oppfolgingsperiode_id" to rs.getString("oppfolgingsperiode_id"),
                            "aokontor" to rs.getString("aokontor"),
                            "arenakontor_start" to rs.getString("arenakontor_start"),
                            "arenakontor_sist_endret_til" to rs.getString("arenakontor_sist_endret_til"),
                            "antall_arena_endringer" to rs.getInt("antall_arena_endringer"),
                            "arenakontor_endret" to rs.getString("arenakontor_endret"),
                            "tid_diff_sek" to rs.getObject("tid_diff_sek") as Long?,
                            "oppfolging_startet_tidspunkt" to rs.getTimestamp("oppfolging_startet_tidspunkt")
                                ?.toInstant()?.toString(),
                            "arena_siste_endret_tidspunkt" to rs.getTimestamp("arena_siste_endret_tidspunkt")
                                ?.toInstant()?.toString(),
                            "datasett_sist_oppdatert" to java.time.Instant.now().toString()

                        )
                    )
                }
            }

            rows
        }


    private fun truncateSnapshotTable() {
        val query = "TRUNCATE TABLE `$DATASET_NAME.avvik_2990_snapshot`"
        bigQuery.query(com.google.cloud.bigquery.QueryJobConfiguration.newBuilder(query).build())
        log.info("BigQuery snapshot-tabell tømt")
    }

    private fun insert2990AvvikRows(rows: List<Map<String, Any?>>) {
        val tableId = TableId.of(DATASET_NAME, "avvik_2990_snapshot")

        val builder = InsertAllRequest.newBuilder(tableId)

        rows.forEach { row ->
            builder.addRow(row)
        }

        val response = bigQuery.insertAll(builder.build())

        if (response.hasErrors()) {
            log.error("BigQuery insert-feil: ${response.insertErrors}")
        } else {
            log.info("BigQuery OK – ${rows.size} rader lastet opp")
        }
    }
}
