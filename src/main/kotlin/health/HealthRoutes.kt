package dab.poao.nav.no.health

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory

typealias CriticalErrorNotificationFunction = () -> Unit

val logger = LoggerFactory.getLogger("no.nav.health.HealthRoutes")

fun Routing.healthEndpoints(): CriticalErrorNotificationFunction {
    var hasCriticalError = false
    val criticalErrorNotificationFunction = {
        logger.error("Setting critical error state")
        hasCriticalError = true
    }

    route("/isAlive") {
        get {
            when (hasCriticalError) {
                true -> call.respond(HttpStatusCode.InternalServerError)
                false -> call.respond(HttpStatusCode.OK)
            }
        }
    }
    route("/isReady") {
        get {
            call.respond(HttpStatusCode.OK)
        }
    }

    return criticalErrorNotificationFunction
}