package no.nav.utils

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import no.nav.db.Fnr
import no.nav.http.graphql.schemas.AlleKontorQueryDto
import no.nav.http.graphql.schemas.KontorHistorikkQueryDto
import no.nav.http.graphql.schemas.KontorTilhorighetQueryDto

@Serializable
data class GraphqlResponse<T> (
    val data: T? = null,
    val errors: List<GraphqlError>? = null,
)

@Serializable
data class GraphqlError (
    val message: String,
    val locations: List<GraphqlErrorLocation>? = null,
    val path: List<String>? = null,
    val extensions: Extension? = null,
)

@Serializable
data class Extension(
    val data: String? = null,
)

@Serializable
data class GraphqlErrorLocation(
    val line: Int,
    val column: Int,
)

@Serializable
data class KontorTilhorighet(
    val kontorTilhorighet: KontorTilhorighetQueryDto?,
)

@Serializable
data class KontorHistorikk(
    val kontorHistorikk: List<KontorHistorikkQueryDto>,
)

@Serializable
data class AlleKontor(
    val alleKontor: List<AlleKontorQueryDto>,
)

private suspend fun HttpClient.graphqlRequest(block: HttpRequestBuilder.() -> Unit): HttpResponse {
    return post("/graphql") {
        contentType(ContentType.Application.Json)
        this.block()
    }
}

suspend fun HttpClient.kontorTilhorighet(fnr: Fnr): HttpResponse {
    return graphqlRequest {
        setBody(kontorTilhorighetQuery(fnr))
    }
}
suspend fun HttpClient.kontoHistorikk(fnr: Fnr): HttpResponse {
    return graphqlRequest {
        setBody(kontorHistorikkQuery(fnr))
    }
}
suspend fun HttpClient.alleKontor(): HttpResponse {
    return graphqlRequest {
        setBody(alleKontorQuery())
    }
}

private fun kontorHistorikkQuery(fnr: Fnr): String {
    return graphqlPayload(fnr, """
            { kontorHistorikk (fnr: \"$fnr\") { kontorId , kilde, endretAv, endretAvType, endretTidspunkt, endringsType } }
        """.trimIndent())
}
fun kontorTilhorighetQuery(fnr: Fnr): String {
    return graphqlPayload(fnr, """
             { kontorTilhorighet (fnr: \"$fnr\") { kontorId , kilde, registrant, registrantType, kontorNavn } }
        """.trimIndent())
}
private fun alleKontorQuery(): String {
    return graphqlPayload(null, """
            { alleKontor { kontorId , navn } }
        """.trimIndent())
}
private fun graphqlPayload(fnr: String?, query: String): String {
    fun variablesClause(fnr: Fnr): String {
        return """
            "variables": { "fnr": "$fnr" },
        """.trimIndent()
    }
    return """
            {
                ${fnr?.let(::variablesClause) ?: ""}
                "query": "$query"
            }
        """.trimIndent()
}