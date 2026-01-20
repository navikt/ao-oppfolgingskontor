package no.nav.http

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import no.nav.db.Ident
import no.nav.db.IdentSomKanLagres
import no.nav.domain.OppfolgingsperiodeId
import no.nav.services.AutomatiskKontorRutingService
import no.nav.services.TilordningFeil
import no.nav.services.TilordningRetry
import no.nav.services.TilordningSuccessIngenEndring
import no.nav.services.TilordningSuccessKontorEndret
import org.slf4j.LoggerFactory
import utils.Outcome
import java.time.ZonedDateTime
import java.util.*

fun Application.configureFinnKontorModule(
    automatiskKontorRutingService: AutomatiskKontorRutingService
) {
    val log = LoggerFactory.getLogger("Application.configureFinnKontorModule")

    val ignorerEksisterendeKontor: suspend (Ident, OppfolgingsperiodeId) -> Outcome<Boolean> =
        { a: Ident, b: OppfolgingsperiodeId -> Outcome.Success(false) }
    val alltidRuting =
        automatiskKontorRutingService.copy(harAlleredeTilordnetAoKontorForOppfolgingsperiode = ignorerEksisterendeKontor)

    routing {
        authenticate("EntraAD") {
            get("/api/finn-kontor") {
                runCatching {
                    log.info("Finner kontor for person")
                    val ident = Json.decodeFromString<IdentSomKanLagres>(call.receiveText())
                    val erArbeidssøker = call.request.queryParameters["arbeidssøker"]?.toBoolean() ?: false
                    val resultat = alltidRuting.tilordneKontorAutomatisk(
                        ident,
                        OppfolgingsperiodeId(UUID.randomUUID()),
                        erArbeidssøker,
                        ZonedDateTime.now().minusMinutes(30)
                    )
                    when (resultat) {
                        is TilordningFeil, is TilordningRetry -> call.respond(HttpStatusCode.InternalServerError)
                        TilordningSuccessIngenEndring -> call.respond(HttpStatusCode.InternalServerError) // TODO: Blir dette riktig? Skal jo være en endring...
                        is TilordningSuccessKontorEndret -> {
                            val tilordning = resultat.kontorEndretEvent.aoKontorEndret?.tilordning
                            call.respond(HttpStatusCode.OK, resultat.kontorId)
                        }
                    }
                }

            }

        }
    }
}
