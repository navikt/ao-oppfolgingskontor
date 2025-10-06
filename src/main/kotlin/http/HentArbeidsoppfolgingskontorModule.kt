package http

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.receive
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import no.nav.db.Ident
import services.KontorTilhorighetBulkService

fun Application.hentArbeidsoppfolgingskontorModule(
    kontorTilhorighetService: KontorTilhorighetBulkService,
) {

    routing {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            })
        }
        authenticate("TilgangsMaskinen") {
            post("api/tilgang/brukers-kontor-bulk") {
                val bulkRequest = call.receive<BulkKontorInboundDto>()
                val identer = bulkRequest.identer.map { Ident.of(it, Ident.HistoriskStatus.UKJENT) }
                kontorTilhorighetService.getKontorTilhorighetBulk(identer)
            }
        }
    }
}

@Serializable
data class BulkKontorInboundDto(
    val identer: List<String>
)