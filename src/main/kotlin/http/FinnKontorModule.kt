package no.nav.http

import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory

fun Application.configureFinnKontorModule() {
    val log = LoggerFactory.getLogger("Application.configureFinnKontorModule")

    routing {
        authenticate("EntraAD") {
            get("/api/finn-kontor") {

            }

        }
    }
}
