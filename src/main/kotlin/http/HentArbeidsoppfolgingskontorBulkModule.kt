package http

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import no.nav.db.Ident
import services.KontorTilhorighetBulkService

const val tilhorighetBulkRoutePath = "api/tilgang/brukers-kontor-bulk"

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
            post(tilhorighetBulkRoutePath) {
                val bulkRequest = call.receive<BulkKontorInboundDto>()
                val identer = bulkRequest.identer.map { Ident.of(it, Ident.HistoriskStatus.UKJENT) }
                val result = kontorTilhorighetService.getKontorTilhorighetBulk(identer)
                    .map {
                        when (it.kontorId) {
                            null -> BulkKontorOutboundDto(
                                it.ident,
                                kontorId = null,
                                httpStatus = 404
                            )
                            else -> BulkKontorOutboundDto(
                                ident = it.ident,
                                kontorId = it.kontorId,
                                httpStatus = 200
                            )
                        }
                    }
                call.respond(HttpStatusCode.MultiStatus, result)
            }
        }
    }
}

@Serializable
data class BulkKontorInboundDto(
    val identer: List<String>
)

@Serializable
data class BulkKontorOutboundDto(
    val ident: String,
    val httpStatus: Int = 404, // 200 eller 404
    val kontorId: String?,
)
