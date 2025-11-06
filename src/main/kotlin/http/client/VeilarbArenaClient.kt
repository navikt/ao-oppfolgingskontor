package http.client

import io.ktor.client.*
import io.ktor.client.call.body
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.accept
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.*
import no.nav.db.Fnr
import no.nav.domain.KontorId
import no.nav.http.client.tokenexchange.SystemTokenPlugin
import no.nav.http.client.tokenexchange.TexasTokenResponse
import java.time.ZonedDateTime

class VeilarbArenaClient(
    val baseUrl: String,
    azureTokenProvider: suspend () -> TexasTokenResponse,
    val httpClient: HttpClient = HttpClient(CIO) {
        defaultRequest {
            url(baseUrl)
        }
        install(Logging) {
            level = LogLevel.INFO
        }
        install(ContentNegotiation) {
            json()
        }
        install(SystemTokenPlugin) { this.tokenProvider = azureTokenProvider }
    }
) {

    // TODO: Vi må være whitelista i veilarboppfølging og ligge i inboundPolicy
    suspend fun hentArenaKontor(fnr: Fnr): KontorId {
        httpClient.post("$baseUrl/hent-oppfolgingsbruker") {
            accept(ContentType.Application.Json)
            setBody(FnrDto(fnr.value))
        }.apply {
            val dto = this.body<OppfolgingsbrukerDto>()
            return KontorId(dto.navKontor ?: error("Fant ikke Arena-kontor for bruker"))
        }
    }

    // TODO: Hvilke felter er egentlig nullable
    private data class OppfolgingsbrukerDto(
        val fodselsnr: String?,
        val formidlingsgruppekode: String?,
        val iservFraDato: ZonedDateTime?,
        val navKontor: String?,
        val kvalifiseringsgruppekode: String?,
        val rettighetsgruppekode: String?,
        val hovedmaalkode: String?,
        val sikkerhetstiltakTypeKode: String?,
        val frKode: String?,
        val harOppfolgingssak: Boolean?,
        val sperretAnsatt: Boolean?,
        val erDoed: Boolean?,
        val doedFraDato: ZonedDateTime?,
        val sistEndretDato: ZonedDateTime?,
    )

    private data class FnrDto(val fnr: String)
}