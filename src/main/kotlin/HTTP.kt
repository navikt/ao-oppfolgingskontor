package no.nav

import dab.poao.nav.no.health.healthEndpoints
import io.ktor.server.application.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.routing.routing

fun Application.configureHealthAndCompression() {
    install(Compression)
    routing {
        healthEndpoints()
    }
    /*
    routing {
        openAPI(path = "openapi", swaggerFile = "openapi/documentation.yaml")
    }
    routing {
        swaggerUI(path = "openapi", swaggerFile = "openapi/documentation.yaml")
    }*/
}
