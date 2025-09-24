package dab.poao.nav.no.health

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Routing.healthEndpoints(): (Boolean) -> Unit {
    val hasCriticalError = { errr: Boolean -> }

    route("/isAlive") {
        get {
            call.respond(HttpStatusCode.OK)
        }
    }
    route("/isReady") {
        get {
            call.respond(HttpStatusCode.OK)
        }
    }
}