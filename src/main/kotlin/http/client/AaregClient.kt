package http.client

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationEnvironment
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import no.nav.db.IdentSomKanLagres
import no.nav.http.client.tokenexchange.ProvideToken
import no.nav.http.client.tokenexchange.TexasTokenFailedResult
import no.nav.http.client.tokenexchange.TexasTokenSuccessResult

fun ApplicationEnvironment.getAaregScope(): String {
    return config.property("apis.aareg.scope").getString()
}

class AaregClient(
    val baseUrl: String,
    val azureTokenProvider: ProvideToken,
    val httpClient: HttpClient = HttpClient(CIO) {
        defaultRequest {
            url(baseUrl)
        }
        install(Logging) {
            level = LogLevel.INFO
        }
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; explicitNulls = false })
        }
    }
) {
    val arbeidsforholdUrl = "/api/v2/arbeidstaker/arbeidsforhold"
    suspend fun hentArbeidsforhold(ident: IdentSomKanLagres): AaregResult {
        return runCatching {
            val tokenResult = azureTokenProvider()
            when(tokenResult) {
                is TexasTokenFailedResult -> return AaregFailure(tokenResult.errorMessage)
                is TexasTokenSuccessResult -> tokenResult.accessToken
            }
            val result = httpClient.post(arbeidsforholdUrl) {
                bearerAuth(tokenResult.accessToken)
                contentType(ContentType.Application.Json)
                setBody(ArbeidsforholdPayload(
                    arbeidstakerId = ident.value
                ))
            }
            result.body<List<ArbeidsforholdDto>>()
        }.fold(
            onSuccess = { AaregSuccess(it) },
            onFailure =  { AaregFailure(it.localizedMessage) })
    }
}

sealed class AaregResult
class AaregSuccess(
    val data: List<ArbeidsforholdDto>
): AaregResult()
class AaregFailure(
    val errorMessage: String
): AaregResult()

@Serializable
data class Arbeidssted(
    /* Liste av identer for underenhet (organisasjonsnummer) eller person (folkeregisterident/aktør-id) */
    val identer: List<ArbeidsforholdIdent>
)

@Serializable
data class ArbeidsforholdIdent(
    val ident: String,
    val type: String,
    val gjeldende: Boolean? = null
)

/* Er en mye større DTO med felter som vi ikke trenger foreløpig */
@Serializable
data class ArbeidsforholdDto(
    val arbeidssted: Arbeidssted,
    val sistBekreftet: String? = null,
    val sistEndret: String? = null,
    val ansettelsesperiode: Ansettelsesperiode
)



@Serializable
data class Ansettelsesperiode(
    val startdato: String,
    val sluttdato: String?
)

@Serializable
data class ArbeidsforholdPayload(
    val arbeidstakerId: String,
    val historikk: Boolean = false,
    val sporingsinformasjon: Boolean = false
)
