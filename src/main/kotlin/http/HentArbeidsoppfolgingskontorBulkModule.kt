package http

import com.nimbusds.jose.util.DefaultResourceRetriever
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import no.nav.db.Ident
import no.nav.security.token.support.v3.RequiredClaims
import no.nav.security.token.support.v3.tokenValidationSupport
import org.slf4j.LoggerFactory
import services.KontorTilhorighetBulkService

const val tilhorighetBulkRoutePath = "/api/tilgang/brukers-kontor-bulk"

fun Application.configureHentArbeidsoppfolgingskontorBulkModule(
    kontorTilhorighetService: KontorTilhorighetBulkService,
) {
    val log = LoggerFactory.getLogger("HentArbeidsoppfolgingskontorBulk")
    val config = environment.config

    routing {
        fun AuthenticationConfig.setupTilgansmaskinAuth() {
            tokenValidationSupport(
                config = config,
                requiredClaims = RequiredClaims(
                    issuer = config.configList("no.nav.security.jwt.issuers").first().property("issuer_name").getString(),
                    claimMap = arrayOf("roles=bulk-hent-kontor")
                ),
                resourceRetriever = DefaultResourceRetriever(),
                name = "TilgangsMaskinen"
            )
        }

        // Do it this way to isolate test setup
        pluginOrNull(Authentication)?.configure { setupTilgansmaskinAuth() }
            ?: install(Authentication) { setupTilgansmaskinAuth() }

        authenticate("TilgangsMaskinen") {
            post(tilhorighetBulkRoutePath) {
                try {
                    val bulkRequest = call.receive<BulkKontorInboundDto>()
                    val identer = bulkRequest.identer.map {
                        Ident.validate(it, Ident.HistoriskStatus.UKJENT)
                    }
                    val result = kontorTilhorighetService.getKontorTilhorighetBulk(identer)
                        .map {
                            BulkKontorOutboundDto(
                                it.ident,
                                kontorId = it.kontorId,
                                httpStatus = if (it.kontorId == null) 404 else 200
                            )
                        }
                    call.respond(HttpStatusCode.MultiStatus, result)
                } catch (e: Throwable) {
                    log.error("Kunne ikke svare på hent kontor bulkspørring: ${e.message}", e)
                    call.respond(HttpStatusCode.InternalServerError, "Noe gikk galt :(")
                }
            }
        }
    }
}

@Serializable
data class BulkKontorInboundDto(
    val identer: List<String>
)

@Serializable
data class BulkKontorOutboundDto(
    val ident: String,
    val httpStatus: Int = 404, // 200 eller 404
    val kontorId: String?,
)
