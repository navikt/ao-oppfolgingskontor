package no.nav.http.client

import io.ktor.client.*
import io.ktor.client.call.body
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import no.nav.domain.KontorId

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
        val response = httpClient.get(hentEnheterPath) {
            accept(ContentType.Application.Json)
        }
        return response.body<List<NorgKontor>>()
            .filter { it.type == "LOKAL" }
            .map { it.toMinimaltKontor() }
    }

    suspend fun hentKontor(kontorId: KontorId): MinimaltNorgKontor {
        val response = httpClient.get(hentEnhetPath(kontorId)) {
            accept(ContentType.Application.Json)
        }
        if (response.status != HttpStatusCode.OK)
            throw RuntimeException("Kunne ikke hente kontor med id $kontorId fra Norg2. Status: ${response.status}")
        return response.body<NorgKontor>().toMinimaltKontor()
    }

    companion object {
        const val hentEnheterPath = "/norg2/api/v1/enhet"
        const val hentEnhetPathWithParam = "/norg2/api/v1/enhet/{enhetId}"
        fun hentEnhetPath(kontorId: KontorId): (String) = "/norg2/api/v1/enhet/${kontorId.id}"
    }
}

data class MinimaltNorgKontor(
    val kontorId: String,
    val navn: String
)

fun NorgKontor.toMinimaltKontor() = MinimaltNorgKontor(
    kontorId = this.enhetNr,
    navn = this.navn
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
