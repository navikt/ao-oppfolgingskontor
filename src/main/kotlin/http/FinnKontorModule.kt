package no.nav.http

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import no.nav.db.IdentSomKanLagres
import no.nav.domain.KontorId
import no.nav.domain.KontorNavn
import no.nav.services.TilordningFeil
import no.nav.services.TilordningResultat
import no.nav.services.TilordningRetry
import no.nav.services.TilordningSuccessIngenEndring
import no.nav.services.TilordningSuccessKontorEndret

fun Application.configureFinnKontorModule(
    dryRunKontorTilordning: suspend (ident: IdentSomKanLagres, erArbeidssøker: Boolean) -> TilordningResultat,
    kontorNavn: suspend (KontorId) -> KontorNavn
) {
    routing {
        authenticate("EntraAD") {
            post("/api/finn-kontor") {
                val (ident, erArbeidssøker) = Json.decodeFromString<FinnKontorInputDto>(call.receiveText())
                val resultat = dryRunKontorTilordning(ident, erArbeidssøker)

                when (resultat) {
                    is TilordningFeil, is TilordningRetry -> call.respond(HttpStatusCode.InternalServerError)
                    is TilordningSuccessIngenEndring -> call.respond(HttpStatusCode.InternalServerError) // TODO: Blir dette riktig? Skal jo være en endring...
                    is TilordningSuccessKontorEndret -> {
                        val tilordning = resultat.kontorEndretEvent.aoKontorEndret?.tilordning ?: return@post call.respond(HttpStatusCode.InternalServerError)
                        val kontorId = tilordning.kontorId
                        val kontorNavn = kontorNavn(kontorId)

                        call.respond(FinnKontorOutputDto(
                            kontorId = kontorId,
                            kontorNavn = kontorNavn
                        ))
                    }
                }
            }
        }
    }
}

@Serializable
data class FinnKontorInputDto(
    val ident: IdentSomKanLagres,
    val erArbeidssøker: Boolean
)

@Serializable
data class FinnKontorOutputDto(
    val kontorId: KontorId,
    val kontorNavn: KontorNavn
)