package no.nav.utils

import kotlinx.serialization.Serializable
import no.nav.db.Fnr
import no.nav.http.graphql.schemas.AlleKontorQueryDto
import no.nav.http.graphql.schemas.KontorHistorikkQueryDto
import no.nav.http.graphql.schemas.KontorQueryDto

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
data class KontorForBruker(
    val kontorForBruker: KontorQueryDto,
)

@Serializable
data class KontorHistorikk(
    val kontorHistorikk: List<KontorHistorikkQueryDto>,
)

@Serializable
data class AlleKontor(
    val alleKontor: List<AlleKontorQueryDto>,
)

fun kontorHistorikkQuery(fnr: Fnr): String {
    return graphqlPayload(fnr, """
            { kontorHistorikk (fnrParam: \"$fnr\") { kontorId , kilde, endretAv, endretAvType, endretTidspunkt, endringsType } }
        """.trimIndent())
}
fun kontorForBrukerQuery(fnr: Fnr): String {
    return graphqlPayload(fnr, """
             { kontorForBruker (fnrParam: \"$fnr\") { kontorId , kilde } }
        """.trimIndent())
}

fun alleKontorQuery(): String {
    return """
            {
            "query": "{ alleKontor { kontorId , navn } }"
            }
        """.trimIndent()
}

fun graphqlPayload(fnr: String, query: String): String {
    return """
            {
                "variables": { "fnr": "$fnr" },
                "query": "$query"
            }
        """.trimIndent()
}