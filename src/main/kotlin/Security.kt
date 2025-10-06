package no.nav

import com.nimbusds.jose.util.DefaultResourceRetriever
import io.ktor.http.HttpStatusCode
import io.ktor.http.cio.Request
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.routing.RoutingCall
import no.nav.domain.NavIdent
import no.nav.domain.Registrant
import no.nav.domain.System
import no.nav.domain.Veileder
import no.nav.security.token.support.v3.TokenValidationContextPrincipal
import no.nav.security.token.support.v3.tokenValidationSupport
import java.util.UUID

fun Application.configureSecurity() {
    val config = environment.config
    install(Authentication) {
        tokenValidationSupport(
            config = config,
            resourceRetriever = DefaultResourceRetriever(),
            name = "EntraAD"
        )
    }
}

sealed class AuthResult
data class Authenticated(val principal: AOPrincipal) : AuthResult()
data class NotAuthenticated(val reason: String) : AuthResult()

fun ApplicationCall.authenticateCall(issuer: String): AuthResult {
    return this.principal<TokenValidationContextPrincipal>()
        ?.resolveAuthContext(issuer)
        ?: NotAuthenticated("TokenValidationContextPrincipal context not in request, not authenticated?")
}

sealed class AOPrincipal
class NavAnsatt(val navIdent: NavIdent, val navAnsattAzureId: UUID) : AOPrincipal()
class SystemPrincipal(val systemName: String) : AOPrincipal()

fun TokenValidationContextPrincipal.resolveAuthContext(issuer: String): AuthResult {
    val idtyp = this.context.getClaims(issuer)?.getStringClaim("idtyp")
    return when {
        idtyp == "app" -> {
            val appName = this.context.getClaims(issuer).getStringClaim("azp_name")
                ?: return NotAuthenticated("azp_name not found in token")
            return Authenticated(SystemPrincipal(appName))
        }
        else -> {
            val navIdent = this.context.getClaims(issuer)?.getStringClaim("NAVident")
                ?: return NotAuthenticated("NAVident not found in token without idtyp == app")
            val navAnsattAzureId = this.context.getClaims(issuer)?.getStringClaim("oid")
                ?.let { UUID.fromString(it) }
                ?: return NotAuthenticated("oid not found in token without idtyp == app")
            Authenticated(NavAnsatt(NavIdent(navIdent), navAnsattAzureId))
        }
    }
}

fun AOPrincipal.toRegistrant(): Registrant {
    return when (this) {
        is NavAnsatt -> Veileder(NavIdent(this.navIdent.id))
        is SystemPrincipal -> System()
    }
}
