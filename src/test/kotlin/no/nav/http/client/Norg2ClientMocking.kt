package no.nav.http.client

import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import no.nav.configureContentNegotiation

val norg2TestUrl = "https://norg2.test.no"

fun ApplicationTestBuilder.mockNorg2Host(): Norg2Client {
    externalServices {
        hosts(norg2TestUrl) {
            configureContentNegotiation()
            routing {
                get(Norg2Client.hentEnhetPath) {
                    call.respond(alleKontor)
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