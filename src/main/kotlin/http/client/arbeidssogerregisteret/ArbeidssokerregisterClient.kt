package no.nav.http.client.arbeidssogerregisteret

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import kotlinx.serialization.json.Json
import no.nav.services.ProfileringFunnet
import no.nav.services.ProfileringIkkeFunnet
import no.nav.services.ProfileringsResultat
import no.nav.services.ProfileringsResultatFeil

fun ApplicationEnvironment.getArbeidssokerregisteretUrl(): String {
    return config.property("apis.arbeidssokerregisteret.url").getString()
}

class ArbeidssokerregisterClient(
    private val baseUrl: String,
    private val azureTokenProvider: () -> String,
    private val client: HttpClient = HttpClient {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                prettyPrint = false
                isLenient = true
            })
        }
    }
) {


    suspend fun hentAggregertPerioder(
        identitetsnummer: String,
        siste: Boolean? = null
    ): ProfileringsResultat {
        try {
            val result = client.post("$baseUrl/api/v1/veileder/arbeidssoekerperioder-aggregert") {
                contentType(ContentType.Application.Json)
                setBody(ArbeidssoekerperiodeRequest(identitetsnummer))

                headers {
                    append(HttpHeaders.Authorization, "Bearer ${azureTokenProvider()}")
                }

                if (siste != null) {
                    url.parameters.append("siste", siste.toString())
                }
            }.body<List<ArbeidssoekerperiodeAggregertResponse>>()

            return result.first { it.tom == null }.profilering?.let { ProfileringFunnet(it.profilertTil) }
                ?: ProfileringIkkeFunnet("Bruker hadde ikke profilering")

        } catch (e: Exception) {
            return ProfileringsResultatFeil(e)
        }
    }
}