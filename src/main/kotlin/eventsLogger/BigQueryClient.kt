package eventsLogger

import com.google.cloud.bigquery.BigQueryOptions
import com.google.cloud.bigquery.InsertAllRequest
import com.google.cloud.bigquery.TableId
import com.google.cloud.Timestamp

class BigQueryClient(projectId: String) {

    private val DATASET_NAME = "kontor_metrikker"
    private val bigQuery = BigQueryOptions.newBuilder().setProjectId(projectId).build().service

    fun sendTestRow() {
        val tableId = TableId.of(DATASET_NAME, "test_table")
        val row = mapOf(
            "id" to System.currentTimeMillis(),
            "name" to "TestBruker",
            "created_at" to Timestamp.now()
        )

        val insertRequest = InsertAllRequest.newBuilder(tableId)
            .addRow(row)
            .build()

        val response = bigQuery.insertAll(insertRequest)

        if (response.hasErrors()) {
            println("Feil ved insert: ${response.insertErrors}")
        } else {
            println("Insert OK! BigQuery-kontakt fungerer.")
        }
    }
}
