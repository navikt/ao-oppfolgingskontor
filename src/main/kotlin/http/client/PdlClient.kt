package no.nav.http.client

import no.nav.http.graphql.generated.client.HentAlder
import com.expediagroup.graphql.client.ktor.GraphQLKtorClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.net.URI
import java.time.LocalDate
import java.time.Period
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

sealed class AlderResult
data class AlderFunnet(val alder: Int) : AlderResult()
data class AlderIkkeFunnet(val message: String) : AlderResult()

class PdlClient(
    pdlGraphqlUrl: String,
    ktorHttpClient: HttpClient = HttpClient(engineFactory = CIO),
) {
    val client = GraphQLKtorClient(
        url = URI.create(pdlGraphqlUrl).toURL(),
        httpClient = ktorHttpClient
    )
    suspend fun hentAlder(fnr: String): AlderResult {
        val query = HentAlder(HentAlder.Variables(fnr))
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
}
