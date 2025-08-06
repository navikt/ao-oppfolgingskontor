package eventsLogger

/*
import com.google.cloud.bigquery.BigQueryOptions
import com.google.cloud.bigquery.InsertAllRequest
import com.google.cloud.bigquery.TableId
 */

interface BigQueryClient {
    fun loggUfordeltBruker()
    fun loggBrukerTilNavUtland() {}
}
/*
class BigQueryClientImplementation(projectId: String) : BigQueryClient {

    private fun TableId.insertRequest(row: Map<String, Any>): InsertAllRequest {
        return InsertAllRequest.newBuilder(this).addRow(row).build()
    }

    val bigQuery = BigQueryOptions.newBuilder().setProjectId(projectId).build().service

    override fun loggUfordeltBruker() {}

    override fun loggBrukerTilNavUtland() {}

}*/
