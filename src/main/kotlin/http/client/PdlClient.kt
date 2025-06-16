package no.nav.http.client

import com.expediagroup.graphql.client.ktor.GraphQLKtorClient
import com.expediagroup.graphql.client.types.GraphQLClientRequest
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationEnvironment
import kotlinx.serialization.Serializable
import no.nav.http.graphql.generated.client.HENT_ALDER_QUERY
import no.nav.http.graphql.generated.client.HENT_FNR_QUERY
import no.nav.http.graphql.generated.client.ID
import no.nav.http.graphql.generated.client.enums.IdentGruppe
import no.nav.http.graphql.generated.client.hentalderquery.Person
import no.nav.http.graphql.generated.client.hentfnrquery.Identliste
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

@Serializable
data class HentFnrQuerySerializable(
    override val variables: Variables,
): GraphQLClientRequest<HentFnrQuerySerializable.Result> {
    override val query: String = HENT_FNR_QUERY
    @Serializable data class Variables(val ident: ID, val grupper: List<IdentGruppe>? = null, val historikk: Boolean)
    data class Result(val hentIdenter: Identliste? = null)
    override fun responseType(): KClass<Result> = Result::class
}

fun ApplicationEnvironment.getPdlScope(): String {
    return config.property("apis.arbeidssokerregisteret.scope").getString()
}

val BehandlingsnummerHeaderPlugin = createClientPlugin("BehandlingsnummerHeaderPlugin") {
    onRequest { request, _ ->
        request.headers.append("Behandlingsnummber", "B884")
    }
}

class PdlClient(
    pdlGraphqlUrl: String,
    private val azureTokenProvider: suspend () -> TexasTokenResponse,
    ktorHttpClient: HttpClient = HttpClient(CIO) {
        install(BehandlingsnummerHeaderPlugin)
        install(Auth) {
            bearer {
                loadTokens {
                    val result = azureTokenProvider()
                    when (result) {
                        is TexasTokenSuccessResult -> BearerTokens(result.accessToken, null)
                        else -> throw IllegalStateException("Kunne ikke hente token fra Azure: $result")
                    }
                }
            }
        }
        install(ContentNegotiation) {
            json()
        }
    }
) {
    val client = GraphQLKtorClient(
        url = URI.create("$pdlGraphqlUrl/graphql").toURL(),
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
        val query = HentFnrQuerySerializable(HentFnrQuerySerializable.Variables(ident = aktorId, historikk = false))
        val result = client.execute(query)
        return result.data?.hentIdenter?.identer
            ?.firstOrNull { it.gruppe == IdentGruppe.FOLKEREGISTERIDENT }
            ?.ident
    }
}
