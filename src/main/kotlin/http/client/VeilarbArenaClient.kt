package http.client

import io.ktor.client.*
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.accept
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.ApplicationEnvironment
import kotlinx.serialization.Serializable
import no.nav.db.Ident
import no.nav.domain.KontorId
import no.nav.http.client.tokenexchange.SystemTokenPlugin
import no.nav.http.client.tokenexchange.TexasTokenResponse
import no.nav.utils.ZonedDateTimeSerializer
import org.slf4j.LoggerFactory
import java.time.ZonedDateTime

fun ApplicationEnvironment.getVeilarbarenaScope(): String {
    return config.property("apis.veilarbarena.scope").getString()
}

class VeilarbArenaClient(
    val baseUrl: String,
    azureTokenProvider: suspend () -> TexasTokenResponse,
    engine: HttpClientEngine = CIO.create(),
) {
    val httpClient: HttpClient = HttpClient(engine) {
        defaultRequest { url(baseUrl) }
        install(Logging) { level = LogLevel.INFO }
        install(ContentNegotiation) { json() }
        install(SystemTokenPlugin) { this.tokenProvider = azureTokenProvider }
    }

    private val logger = LoggerFactory.getLogger(this::class.java)

    suspend fun hentArenaKontor(ident: Ident): ArenakontorResult {
        val url = "$baseUrl/veilarbarena/api/v3/hent-oppfolgingsbruker"
        logger.info("Henter Arenakontor fra url: $url")
        return try {
            val response = httpClient.post(url) {
                accept(ContentType.Application.Json)
                contentType(ContentType.Application.Json)
                setBody(FnrDto(ident.value))
            }

            when (response.status) {
                HttpStatusCode.NotFound -> ArenakontorIkkeFunnet()
                HttpStatusCode.OK -> {
                    val dto = response.body<OppfolgingsbrukerDto>()
                    ArenakontorFunnet(KontorId(dto.navKontor), dto.sistEndretDato)
                }

                else -> ArenakontorOppslagFeilet(
                    RuntimeException("Uventet HTTP-status: ${response.status}")
                )
            }
        } catch (e: Exception) {
            ArenakontorOppslagFeilet(e)
        }
    }

    // TODO: Hvilke felter er egentlig nullable
    @Serializable
    private data class OppfolgingsbrukerDto(
        val fodselsnr: String?,
        val formidlingsgruppekode: String?,
        @Serializable(with = ZonedDateTimeSerializer::class)
        val iservFraDato: ZonedDateTime?,
        val navKontor: String,
        val kvalifiseringsgruppekode: String?,
        val rettighetsgruppekode: String?,
        val hovedmaalkode: String?,
        val sikkerhetstiltakTypeKode: String?,
        val frKode: String?,
        val harOppfolgingssak: Boolean?,
        val sperretAnsatt: Boolean?,
        val erDoed: Boolean?,
        @Serializable(with = ZonedDateTimeSerializer::class)
        val doedFraDato: ZonedDateTime?,
        @Serializable(with = ZonedDateTimeSerializer::class)
        val sistEndretDato: ZonedDateTime,
    )

    @Serializable
    private data class FnrDto(val fnr: String)
}

sealed class ArenakontorResult
class ArenakontorFunnet(val kontorId: KontorId, val sistEndret: ZonedDateTime) : ArenakontorResult()
class ArenakontorIkkeFunnet : ArenakontorResult()
class ArenakontorOppslagFeilet(val e: Exception) : ArenakontorResult()