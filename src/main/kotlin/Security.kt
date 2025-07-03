package no.nav

import com.nimbusds.jose.util.DefaultResourceRetriever
import io.ktor.server.application.*
import io.ktor.server.auth.*
import no.nav.domain.NavIdent
import no.nav.domain.Registrant
import no.nav.domain.System
import no.nav.domain.Veileder
import no.nav.security.token.support.v3.TokenValidationContextPrincipal
import no.nav.security.token.support.v3.tokenValidationSupport
import java.util.UUID

fun Application.configureSecurity() {

    install(Authentication) {
        tokenValidationSupport(
            config = this@configureSecurity.environment.config,
            resourceRetriever = DefaultResourceRetriever()
        )
    }
}

fun ApplicationCall.authenticate(issuer: String): AOPrincipal {
    return this.principal<TokenValidationContextPrincipal>()
        ?.resolveAuthContext(issuer) ?: throw IllegalStateException("TokenValidationContextPrincipal context not in request, not authenticated?")
}

sealed class AOPrincipal
class NavAnsatt(
    val navIdent: NavIdent,
    val navAnsattAzureId: UUID) : AOPrincipal()
class SystemPrincipal(
    val systemName: String) : AOPrincipal()

fun TokenValidationContextPrincipal.resolveAuthContext(issuer: String): AOPrincipal {
    val idtyp = this.context.getClaims(issuer).getStringClaim("idtyp")
    return when {
        idtyp == "app" -> {
            val appName = this.context.getClaims(issuer).getStringClaim("azp_name")
                ?: throw IllegalStateException("azp_name not found in token")
            return SystemPrincipal(appName)
        }
        else -> {
            val navIdent = this.context.getClaims(issuer).getStringClaim("NAVident")
            val navAnsattAzureId = this.context.getClaims(issuer).getStringClaim("oid")
                .let { UUID.fromString(it) }
            NavAnsatt(NavIdent(navIdent), navAnsattAzureId)
        }
    }
}

fun AOPrincipal.toRegistrant(): Registrant {
    return when (this) {
        is NavAnsatt -> Veileder(NavIdent(this.navIdent.id))
        is SystemPrincipal -> System()
    }
}