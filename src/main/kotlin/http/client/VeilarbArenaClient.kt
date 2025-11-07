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
import no.nav.db.Ident
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
    suspend fun hentArenaKontor(ident: Ident): ArenakontorResult {
        try {
            httpClient.post("$baseUrl/hent-oppfolgingsbruker") {
                accept(ContentType.Application.Json)
                setBody(FnrDto(ident.value))
            }.apply {
                val dto = this.body<OppfolgingsbrukerDto>()
                return if (dto.navKontor == null) ArenakontorIkkeFunnet()
                else ArenakontorFunnet(KontorId(dto.navKontor), dto.sistEndretDato)
            }
        } catch (e: Exception) {
            return ArenakontorOppslagFeilet(e)
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
        val sistEndretDato: ZonedDateTime,
    )

    private data class FnrDto(val fnr: String)
}

sealed class ArenakontorResult
class ArenakontorFunnet(val kontorId: KontorId, val sistEndret: ZonedDateTime) : ArenakontorResult()
class ArenakontorIkkeFunnet : ArenakontorResult()
class ArenakontorOppslagFeilet(val e: Exception) : ArenakontorResult()