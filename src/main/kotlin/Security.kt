package no.nav

import com.nimbusds.jose.util.DefaultResourceRetriever
import io.ktor.server.application.*
import io.ktor.server.auth.*
import no.nav.security.token.support.v3.tokenValidationSupport

fun Application.configureSecurity() {

    install(Authentication) {
        tokenValidationSupport(
            config = this@configureSecurity.environment.config,
            resourceRetriever = DefaultResourceRetriever()
        )
    }
}
