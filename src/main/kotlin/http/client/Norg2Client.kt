package no.nav.http.client

import io.ktor.client.*
import io.ktor.client.call.body
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable

class Norg2Client(
    val baseUrl: String,
    val httpClient: HttpClient = HttpClient(CIO) {
        defaultRequest {
            url(baseUrl)
        }
        install(Logging)
        install(ContentNegotiation) {
            json()
        }
    }
) {
    suspend fun hentAlleEnheter(): List<MinimaltNorgKontor> {
        val response = httpClient.get(hentEnhetPath)
        return response.body<List<NorgKontor>>()
            .filter { it.type == "LOKAL" }
            .map { MinimaltNorgKontor(it.enhetNr, it.navn) }
    }

    companion object {
        const val hentEnhetPath = "/norg2/api/v1/enhet"
    }
}

data class MinimaltNorgKontor(
    val kontorId: String,
    val navn: String
)

@Serializable
data class NorgKontor(
    val enhetId: Long,
    val navn: String,
    val enhetNr: String,
    val antallRessurser: Int,
    val status: String,
    val orgNivaa: String,
    val type: String,
    val organisasjonsnummer: String?,
    val underEtableringDato: String?,
    val aktiveringsdato: String?,
    val underAvviklingDato: String?,
    val nedleggelsesdato: String?,
    val oppgavebehandler: Boolean,
    val versjon: Int,
    val sosialeTjenester: String?,
    val kanalstrategi: String?,
    val orgNrTilKommunaltNavKontor: String?
)