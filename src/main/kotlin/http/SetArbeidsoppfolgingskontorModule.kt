package no.nav.http

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.*
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import no.nav.db.Fnr
import no.nav.db.table.ArbeidsOppfolgingKontorTable
import no.nav.db.table.KontorhistorikkTable.fnr
import no.nav.security.token.support.v3.TokenValidationContextPrincipal
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert
import org.slf4j.LoggerFactory

val logger = LoggerFactory.getLogger("Applcation.configureArbeidsoppfolgingskontorModule")

fun Application.configureArbeidsoppfolgingskontorModule() {
    val issuer = environment.config.property("auth.entraIssuer").getString()

    routing {
        install(ContentNegotiation) {
            json()
        }
        authenticate {
            post("/api/kontor") {
                runCatching {
                    val kontor = call.receive<ArbeidsoppfolgingsKontorTilordningDTO>()
                    val principal = call.principal<TokenValidationContextPrincipal>()
                    log.debug("Issuers : ${principal?.context?.issuers?.joinToString(", ") { it }}")
                    log.debug("claims : ${principal?.context?.getClaims(issuer)?.allClaims?.keys?.joinToString(", ") }")
                    val veilederIdent = principal?.context?.getClaims(issuer)?.getStringClaim("NAVIdent")
                        ?: throw IllegalStateException("NAVIdent not found in token")

                    transaction {
                        ArbeidsOppfolgingKontorTable.upsert {
                            it[kontorId] = kontor.kontorId
                            it[fnr] = kontor.fnr
                            it[endretAv] = veilederIdent
                            it[endretAvType] = "VEILEDER"
                        }
                    }
                }
                    .onSuccess {
                        it
                        call.respondText("OK", status = HttpStatusCode.OK)
                    }
                    .onFailure {
                        logger.error("Kunne ikke oppdatere kontor", it)
                        call.respondText("Kunne ikke oppdatere kontor", status = HttpStatusCode.InternalServerError)
                    }
            }
        }
    }
}

@Serializable
data class ArbeidsoppfolgingsKontorTilordningDTO(
    val kontorId: String,
    val begrunnelse: String?,
    val fnr: Fnr
)