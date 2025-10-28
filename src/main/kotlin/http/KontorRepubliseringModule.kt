package no.nav.http

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import services.KontorRepubliseringService

fun Application.configureKontorRepubliseringModule(
    kontorRepubliseringService: KontorRepubliseringService
) {
    val log = LoggerFactory.getLogger("Application.configureKontorRepubliseringModule")

    routing {
        authenticate("EntraAD") { // TODO: Fix admintilgang
            post("/admin/republiser-arbeidsoppfolgingskontorendret") {
                runCatching {
                    launch {
                        log.info("Starter republisering av kontorer...")
                        kontorRepubliseringService.republiserKontorer()
                        log.info("Fullført republisering av kontorer.")
                    }
                    call.respond(HttpStatusCode.Accepted, "Republisering startet")
                }.onFailure { e ->
                    log.error("Feil ved republisering av kontorer", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        "Klarte ikke starte republisering av kontorer"
                    )
                }
            }
        }
    }
}
