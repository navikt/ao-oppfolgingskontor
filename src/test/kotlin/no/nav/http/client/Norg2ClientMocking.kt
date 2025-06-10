package no.nav.http.client

import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.Logging
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import no.nav.http.logger
import org.slf4j.LoggerFactory

val norg2TestUrl = "https://norg2.test.no"

val logger = LoggerFactory.getLogger("ApplicationTestBuilder.mockNorg2Host")

fun ApplicationTestBuilder.mockNorg2Host(): Norg2Client {
    logger.info("Mocking norg2 host: $norg2TestUrl${Norg2Client.hentEnhetPathWithParam}")
    externalServices {
        hosts(norg2TestUrl) {
            routing {
                install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) {
                    json()
                }
                get(Norg2Client.hentEnheterPath) {
                    val fileContent = javaClass.getResource("/norg2enheter.json")?.readText()
                        ?: throw IllegalStateException("File norg2enheter.json not found")
                    call.respondText(fileContent, ContentType.Application.Json)
                }
                get(Norg2Client.hentEnhetPathWithParam) {
                    val enhetId = call.pathParameters["enhetId"] ?: throw IllegalArgumentException("EnhetId not found in path")
                    val kontor = NorgKontor(
                        enhetId = enhetId.toLong(),
                        navn = "NAV test",
                        enhetNr = enhetId,
                        antallRessurser = 2,
                        status = "AKTIV",
                        orgNivaa = "4",
                        type = "LOKAL",
                        organisasjonsnummer = "123456789",
                        underEtableringDato = null,
                        aktiveringsdato = null,
                        underAvviklingDato = null,
                        nedleggelsesdato = null,
                        oppgavebehandler = true,
                        versjon = 1,
                        sosialeTjenester = null,
                        kanalstrategi = null,
                        orgNrTilKommunaltNavKontor = null,
                    )
                    call.respond(kontor)
                }
            }
        }
    }
    return Norg2Client(
        baseUrl = norg2TestUrl,
        httpClient = createClient {
            install(ContentNegotiation) { json() }
            install(Logging)
            defaultRequest {
                url(norg2TestUrl)
            }
        }
    )
}

val alleKontor = listOf(
    NorgKontor(
        enhetId = 1,
        enhetNr = "4142",
        navn = "NAV Oslo",
        type = "LOKAL",
        antallRessurser = 10,
        status = "AKTIV",
        orgNivaa = "NAV_KONTOR",
        organisasjonsnummer = "123456789",
        underEtableringDato = null,
        aktiveringsdato = null,
        underAvviklingDato = null,
        nedleggelsesdato = null,
        oppgavebehandler = true,
        versjon = 1,
        sosialeTjenester = null,
        kanalstrategi = null,
        orgNrTilKommunaltNavKontor = null
    )
)