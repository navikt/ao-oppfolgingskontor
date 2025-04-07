package dab.poao.nav.no.health

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Routing.healthEndpoints() {
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