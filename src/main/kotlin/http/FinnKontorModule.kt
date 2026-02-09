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
import org.slf4j.LoggerFactory

fun Application.configureFinnKontorModule(
    simulerKontorTilordning: suspend (ident: IdentSomKanLagres, erArbeidssøker: Boolean) -> TilordningResultat,
    kontorNavn: suspend (KontorId) -> KontorNavn
) {
    val log = LoggerFactory.getLogger("FinnKontorModule")

    routing {
        authenticate("EntraAD") {
            post("/api/finn-kontor") {
                val (ident, erArbeidssøker) = Json.decodeFromString<FinnKontorInputDto>(call.receiveText())
                val resultat = simulerKontorTilordning(ident, erArbeidssøker)

                when (resultat) {
                    is TilordningFeil, is TilordningRetry, is TilordningSuccessIngenEndring -> {
                        log.warn("Uventet resultat ved finn-kontor: $resultat")
                        call.respond(HttpStatusCode.InternalServerError)
                    }
                    is TilordningSuccessKontorEndret -> {
                        val aoKontorEndret = resultat.kontorEndretEvent.aoKontorEndret
                        if (aoKontorEndret == null) {
                            log.warn("Fikk ikke ao-kontorendring på finn-kontor")
                            return@post call.respond(HttpStatusCode.InternalServerError)
                        }
                        val kontorId = aoKontorEndret.tilordning.kontorId
                        val kontorNavn = kontorNavn(kontorId)

                        call.respond(FinnKontorOutputDto(
                            kontorId = kontorId.id,
                            kontorNavn = kontorNavn.navn
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
    val kontorId: String,
    val kontorNavn: String
)