package no.nav.http.client.arbeidssogerregisteret

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import no.nav.http.client.tokenexchange.SystemTokenPlugin
import no.nav.http.client.tokenexchange.TexasTokenResponse
import no.nav.services.HentProfileringsResultat
import no.nav.services.ProfileringFunnet
import no.nav.services.ProfileringIkkeFunnet
import no.nav.services.ProfileringsResultatFeil
import org.slf4j.LoggerFactory

fun ApplicationEnvironment.getArbeidssokerregisteretUrl(): String {
    return config.property("apis.arbeidssokerregisteret.url").getString()
}

fun ApplicationEnvironment.getArbeidssokerregisteretScope(): String {
    return config.property("apis.arbeidssokerregisteret.scope").getString()
}

class ArbeidssokerregisterClient(
    private val baseUrl: String,
    private val client: HttpClient
) {
    val log = LoggerFactory.getLogger(javaClass)

    constructor(
        baseUrl: String,
        azureTokenProvider: suspend () -> TexasTokenResponse,
    ) : this(
        baseUrl,
        HttpClient(CIO) {
            install(SystemTokenPlugin) {
                this.tokenProvider = azureTokenProvider
            }
            install(Logging)
            install(ContentNegotiation) {
                json()
            }
        }
    )

    suspend fun hentProfilering(
        identitetsnummer: String
    ): HentProfileringsResultat {
        try {
            val result = client.post("$baseUrl/api/v1/veileder/arbeidssoekerperioder-aggregert") {
                contentType(ContentType.Application.Json)
                setBody(ArbeidssoekerperiodeRequest(identitetsnummer))
                url.parameters.append("siste", "true")
            }
                .body<List<ArbeidssoekerperiodeAggregertResponse>>()

            val currentOpenPeriod = result.firstOrNull { it.avsluttet == null }
            if (currentOpenPeriod == null) {
                return ProfileringIkkeFunnet("Ingen åpen arbeidssøkerperiode.")
            }

            return currentOpenPeriod.opplysningerOmArbeidssoeker
                .firstOrNull()
                ?.profilering
                ?.profilertTil?.let { profilertTil ->
                    when (profilertTil) {
                        ProfileringsResultat.UKJENT_VERDI -> ProfileringIkkeFunnet("Ukjent verdi for profilering.")
                        else -> ProfileringFunnet(profilertTil)
                    }
                }
                ?: ProfileringIkkeFunnet("Bruker hadde ikke profilering")


        } catch (e: Exception) {
            log.error(e.message, e)
            return ProfileringsResultatFeil(e)
        }
    }
}