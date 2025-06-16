package no.nav.http.client

import com.expediagroup.graphql.client.Generated
import com.expediagroup.graphql.client.ktor.GraphQLKtorClient
import com.expediagroup.graphql.client.types.GraphQLClientRequest
import com.fasterxml.jackson.annotation.JsonProperty
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientEngineContainer
import io.ktor.client.engine.cio.CIO
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import no.nav.http.graphql.generated.client.HENT_ALDER_QUERY
import no.nav.http.graphql.generated.client.HentAlderQuery
import no.nav.http.graphql.generated.client.HentFnrQuery
import no.nav.http.graphql.generated.client.ID
import no.nav.http.graphql.generated.client.enums.IdentGruppe
import no.nav.http.graphql.generated.client.hentalderquery.Person
import java.net.URI
import java.time.LocalDate
import java.time.Period
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.reflect.KClass

sealed class AlderResult
data class AlderFunnet(val alder: Int) : AlderResult()
data class AlderIkkeFunnet(val message: String) : AlderResult()

@Serializable
data class HentAlderQuerySerializable(
    override val variables: Variables,
    ) : GraphQLClientRequest<HentAlderQuerySerializable.Result> {
    override val query: String = HENT_ALDER_QUERY
    override val operationName: String = "hentAlderQuery"
    @Serializable data class Variables(val ident: ID)
    data class Result(val hentPerson: Person? = null)
    override fun responseType(): KClass<Result> = Result::class
}

class PdlClient(
    pdlGraphqlUrl: String,
    ktorHttpClient: HttpClient = HttpClient(engineFactory = CIO),
) {
    val client = GraphQLKtorClient(
        url = URI.create(pdlGraphqlUrl).toURL(),
        httpClient = ktorHttpClient
    )
    suspend fun hentAlder(fnr: String): AlderResult {
        val query = HentAlderQuerySerializable(HentAlderQuerySerializable.Variables(fnr))
        val result = client.execute(query)
        if (result.errors != null && result.errors!!.isNotEmpty()) {
            return AlderIkkeFunnet(result.errors!!.joinToString { it.message })
        } else {
            val alder = result.data?.hentPerson?.foedselsdato?.firstOrNull()?.foedselsdato
                ?.let { LocalDate.parse(it, DateTimeFormatter.ISO_LOCAL_DATE) }
                ?.let { Period.between(ZonedDateTime.now().toLocalDate(), it).years } // TODO: Verify this is actually correct
            return if (alder == null) {
                AlderIkkeFunnet("Alder kunne ikke beregnes fra fødselsdato")
            } else {
                AlderFunnet(alder)
            }
        }
    }

    suspend fun hentFnrFraAktorId(aktorId: String): String? {
        val query = HentFnrQuery(HentFnrQuery.Variables(ident = aktorId, historikk = false))
        val result = client.execute(query)
        return result.data?.hentIdenter?.identer
            ?.firstOrNull { it.gruppe == IdentGruppe.FOLKEREGISTERIDENT }
            ?.ident
    }
}
