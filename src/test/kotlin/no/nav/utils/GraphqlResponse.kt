package no.nav.utils

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import no.nav.db.Fnr
import no.nav.db.Ident
import no.nav.http.client.logger
import no.nav.http.graphql.schemas.AlleKontorQueryDto
import no.nav.http.graphql.schemas.ArbeidsoppfolgingKontorDto
import no.nav.http.graphql.schemas.ArenaKontorDto
import no.nav.http.graphql.schemas.GeografiskTilknyttetKontorDto
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
data class AlleKontorTilhorigheter(
    val arena: ArenaKontorDto?,
    val geografiskTilknytning: GeografiskTilknyttetKontorDto?,
    val arbeidsoppfolging: ArbeidsoppfolgingKontorDto?,
)
@Serializable
data class KontorTilhorigheter(
    val kontorTilhorigheter: AlleKontorTilhorigheter,
)

@Serializable
data class KontorHistorikk(
    val kontorHistorikk: List<KontorHistorikkQueryDto>,
)

@Serializable
data class AlleKontor(
    val alleKontor: List<AlleKontorQueryDto>,
)

private suspend fun HttpClient.graphqlRequest(bearerToken: String? = null, block: HttpRequestBuilder.() -> Unit): HttpResponse {
    return post("/graphql") {
        contentType(ContentType.Application.Json)
        if (bearerToken != null) {
            bearerAuth(bearerToken)
        }
        this.block()
    }
}

suspend fun HttpClient.kontorTilhorighet(fnr: Fnr, bearerToken: String? = null): HttpResponse {
    return graphqlRequest(bearerToken) {
        setBody(kontorTilhorighetQuery(fnr))
    }
}
suspend fun HttpClient.alleKontorTilhorigheter(fnr: Fnr, bearerToken: String? = null): HttpResponse {
    return graphqlRequest(bearerToken) {
        setBody(alleKontorTilhorigheterQuery(fnr).also { logger.info("GRAPHQL-query: $it") })
    }
}
suspend fun HttpClient.kontorHistorikk(fnr: Fnr, bearerToken: String? = null): HttpResponse {
    return graphqlRequest(bearerToken) {
        setBody(kontorHistorikkQuery(fnr))
    }
}
suspend fun HttpClient.alleKontor(ident: Ident?, bearerToken: String? = null): HttpResponse {
    return graphqlRequest(bearerToken) {
        setBody(alleKontorQuery(ident))
    }
}

val pesos = "$"
val identArg = "${pesos}ident"

private fun kontorHistorikkQuery(ident: Ident): String {
    return graphqlPayload(ident, """
            { kontorHistorikk (ident: \"$ident\") { kontorId , kontorType, endretAv, endretAvType, endretTidspunkt, endringsType } }
        """.trimIndent())
}
fun kontorTilhorighetQuery(ident: Ident): String {
    return graphqlPayload(ident, """
             { kontorTilhorighet (ident: \"$ident\") { kontorId , kontorType, registrant, registrantType, kontorNavn } }
        """.trimIndent())
}

fun alleKontorTilhorigheterQuery(ident: Ident): String {
    return graphqlPayload(ident, """
             query kontorTilhorigheterQuery($identArg: String!) {
                kontorTilhorigheter (ident: $identArg) {
                     arena { kontorId, kontorNavn }
                     arbeidsoppfolging { kontorId, kontorNavn }
                     geografiskTilknytning { kontorId, kontorNavn }
                }
            }
        """.replace("\n", "").trimIndent())
}
private fun alleKontorQuery(ident: Ident?): String {
    return graphqlPayload(ident, """
           query alleKontorQuery($identArg: String) { alleKontor(ident: $identArg) { kontorId , kontorNavn } }
        """.trimIndent())
}
private fun graphqlPayload(ident: Ident?, query: String): String {
    fun variablesClause(ident: Ident): String {
        return """
            "variables": { "ident": "$ident" },
        """.trimIndent()
    }
    return """
            {
                ${ident?.let(::variablesClause) ?: ""}
                "query": "$query"
            }
        """.trimIndent()
}