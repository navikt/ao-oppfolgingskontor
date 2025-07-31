package no.nav.http.client

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationEnvironment
import kotlinx.serialization.Serializable
import no.nav.db.Ident
import no.nav.domain.HarSkjerming
import no.nav.http.client.tokenexchange.SystemTokenPlugin
import no.nav.http.client.tokenexchange.TexasTokenResponse
import org.slf4j.LoggerFactory

fun ApplicationEnvironment.getSkjermedePersonerUrl(): String {
    return config.property("apis.skjermedePersoner.url").getString()
}
fun ApplicationEnvironment.getSkjermedePersonerScope(): String {
    return config.property("apis.skjermedePersoner.scope").getString()
}

class SkjermingsClient(
    val httpClient: HttpClient
) {
    constructor(baseUrl: String, azureTokenProvider: suspend () -> TexasTokenResponse): this(
        HttpClient(CIO) {
            defaultRequest { url(baseUrl) }
            install(SystemTokenPlugin) { this.tokenProvider = azureTokenProvider }
            install(Logging) { level = LogLevel.INFO }
            install(ContentNegotiation) { json() }
        }
    )

    val log = LoggerFactory.getLogger(this::class.java)

    suspend fun hentSkjerming(fnr: Ident): SkjermingResult {
        return try {
            val response = httpClient.post("/skjermet") {
                contentType(ContentType.Application.Json)
                setBody(SkjermingRequestDto(fnr.value))
            }
            if (response.status.isSuccess()) {
                SkjermingFunnet(HarSkjerming(response.body<Boolean>()))
            } else {
                SkjermingIkkeFunnet("Kunne ikke hente skjermingsstatus. Status: ${response.status}")
            }
        } catch (e: Exception) {
            log.error("Feil ved henting av skjermingsstatus", e)
            SkjermingIkkeFunnet("Feil ved henting av skjermingsstatus: ${e.message}")
        }
    }
}

@Serializable
data class SkjermingRequestDto(
    val personident: String
)

sealed class SkjermingResult
class SkjermingFunnet(val skjermet: HarSkjerming) : SkjermingResult()
class SkjermingIkkeFunnet(val melding: String) : SkjermingResult()
