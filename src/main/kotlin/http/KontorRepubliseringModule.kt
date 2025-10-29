package no.nav.http

import com.nimbusds.jose.util.DefaultResourceRetriever
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.launch
import no.nav.security.token.support.v3.RequiredClaims
import no.nav.security.token.support.v3.tokenValidationSupport
import org.slf4j.LoggerFactory
import services.KontorRepubliseringService

fun Application.configureKontorRepubliseringModule(
    kontorRepubliseringService: KontorRepubliseringService
) {
    val log = LoggerFactory.getLogger("Application.configureKontorRepubliseringModule")
    val config = environment.config

    routing {
        fun AuthenticationConfig.setUpKontorRepubliseringAuth() {
            tokenValidationSupport(
                config = config,
//                requiredClaims = RequiredClaims(
//                    issuer = config.configList("no.nav.security.jwt.issuers").first().property("issuer_name").getString(),
//                    claimMap = arrayOf("scp=republiser-kontor"),
//                ),
                resourceRetriever = DefaultResourceRetriever(),
                name = "poaoAdmin"
            )
        }

        pluginOrNull(Authentication)?.configure { setUpKontorRepubliseringAuth() }
            ?: install(Authentication) { setUpKontorRepubliseringAuth() }

        authenticate("poaoAdmin") {
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
