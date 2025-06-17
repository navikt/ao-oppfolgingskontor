package no.nav.http.client.arbeidssogerregisteret

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import no.nav.http.client.tokenexchange.SystemTokenPlugin
import no.nav.http.client.tokenexchange.TexasTokenResponse
import no.nav.http.client.tokenexchange.TexasTokenSuccessResult
import no.nav.services.ProfileringFunnet
import no.nav.services.ProfileringIkkeFunnet
import no.nav.services.ProfileringsResultat
import no.nav.services.ProfileringsResultatFeil

fun ApplicationEnvironment.getArbeidssokerregisteretUrl(): String {
    return config.property("apis.arbeidssokerregisteret.url").getString()
}

fun ApplicationEnvironment.getArbeidssokerregisteretScope(): String {
    return config.property("apis.arbeidssokerregisteret.scope").getString()
}

class ArbeidssokerregisterClient(
    private val baseUrl: String,
    private val azureTokenProvider: suspend () -> TexasTokenResponse,
    private val client: HttpClient = HttpClient(CIO) {
        install(SystemTokenPlugin) {
            this.tokenProvider = azureTokenProvider
        }
        install(ContentNegotiation) {
            json()
        }
    }
) {

    suspend fun hentProfilering(
        identitetsnummer: String
    ): ProfileringsResultat {
        try {
            val oboTokenResult = azureTokenProvider()
            val token: TexasTokenSuccessResult = when (oboTokenResult) {
                is TexasTokenSuccessResult -> oboTokenResult
                else -> return ProfileringsResultatFeil(IllegalStateException("Ugyldig token type mottatt fra Azure"))
            }
            val result = client.post("$baseUrl/api/v1/veileder/arbeidssoekerperioder-aggregert") {
                contentType(ContentType.Application.Json)
                setBody(ArbeidssoekerperiodeRequest(identitetsnummer))
                url.parameters.append("siste", "true")
                headers {
                    append(HttpHeaders.Authorization, "Bearer ${token}")
                }
            }.body<List<ArbeidssoekerperiodeAggregertResponse>>()

            return result.first { it.tom == null }.profilering?.let { ProfileringFunnet(it.profilertTil) }
                ?: ProfileringIkkeFunnet("Bruker hadde ikke profilering")

        } catch (e: Exception) {
            return ProfileringsResultatFeil(e)
        }
    }
}