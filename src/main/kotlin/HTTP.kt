package no.nav

import dab.poao.nav.no.health.CriticalErrorNotificationFunction
import dab.poao.nav.no.health.healthEndpoints
import io.ktor.server.application.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.routing.routing
import kotlin.time.Duration.Companion.seconds

fun Application.configureHealthAndCompression(): CriticalErrorNotificationFunction {
    install(Compression)

    lateinit var criticalErrorNotificationFunction: CriticalErrorNotificationFunction

    routing {
        criticalErrorNotificationFunction = healthEndpoints()
    }
    /*
    routing {
        openAPI(path = "openapi", swaggerFile = "openapi/documentation.yaml")
    }
    routing {
        swaggerUI(path = "openapi", swaggerFile = "openapi/documentation.yaml")
    }*/
    return criticalErrorNotificationFunction
}

fun Application.configureRateLimit() {
    install(RateLimit) {
        global {
            // Maks 200 requests per sekund per IP-adresse
            rateLimiter(limit = 200, refillPeriod = 1.seconds)
            requestKey { call ->
                call.request.origin.remoteHost
            }
        }
    }
}