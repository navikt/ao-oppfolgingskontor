package no.nav.http

import http.handlers.SettKontorHandler
import http.handlers.SettKontorFailure
import http.handlers.SettKontorSuccess
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import no.nav.AOPrincipal
import no.nav.Authenticated
import no.nav.NotAuthenticated
import no.nav.audit.AuditLogger
import no.nav.audit.toAuditEntry
import no.nav.audit.traceId
import no.nav.authenticateCall
import no.nav.db.IdentSomKanLagres
import no.nav.domain.events.KontorEndretEvent
import no.nav.domain.events.KontorSattAvVeileder
import no.nav.getIssuer
import no.nav.http.client.HarStrengtFortroligAdresseResult
import no.nav.http.client.SkjermingResult
import no.nav.http.client.poaoTilgang.PoaoTilgangKtorHttpClient
import no.nav.http.graphql.AuthenticateRequest
import no.nav.services.KontorNavnService
import no.nav.services.KontorTilhorighetService
import no.nav.services.KontorTilordningService
import org.slf4j.LoggerFactory
import services.OppfolgingsperiodeService

val logger = LoggerFactory.getLogger("Application.configureArbeidsoppfolgingskontorModule")

fun Application.configureArbeidsoppfolgingskontorModule(
    kontorNavnService: KontorNavnService,
    kontorTilhorighetService: KontorTilhorighetService,
    poaoTilgangClient: PoaoTilgangKtorHttpClient,
    oppfolgingsperiodeService: OppfolgingsperiodeService,
    kontorTilordningService: KontorTilordningService,
    publiserKontorEndring: suspend (KontorSattAvVeileder) -> Result<Unit>,
    authenticateRequest: AuthenticateRequest = { req -> req.call.authenticateCall(environment.getIssuer()) },
    hentSkjerming: suspend (IdentSomKanLagres) -> SkjermingResult,
    hentAdresseBeskyttelse: suspend (IdentSomKanLagres) -> HarStrengtFortroligAdresseResult,
    brukAoRuting: Boolean
) {
    val log = LoggerFactory.getLogger("Application.configureArbeidsoppfolgingskontorModule")

    val settKontorHandler = SettKontorHandler(
        kontorNavnService::getKontorNavn,
        { principal: AOPrincipal, ident: IdentSomKanLagres -> kontorTilhorighetService.getArbeidsoppfolgingKontorTilhorighet(ident) },
        { principal, ident, traceId ->
            poaoTilgangClient.harLeseTilgang(principal, ident, traceId)
                .also { AuditLogger.logSettKontor(it.toAuditEntry()) }
        },
        oppfolgingsperiodeService::getCurrentOppfolgingsperiode,
        { event: KontorEndretEvent, brukAoRuting2: Boolean -> kontorTilordningService.tilordneKontor(event, brukAoRuting2) },
        publiserKontorEndring,
        hentSkjerming,
        hentAdresseBeskyttelse,
        brukAoRuting,
    )

    routing {
        authenticate("EntraAD") {
            post("/api/kontor") {
                try {
                    val kontorTilordning = call.receive<ArbeidsoppfolgingsKontorTilordningDTO>()
                    val principal = when (val authresult = authenticateRequest(call.request)) {
                        is Authenticated -> authresult.principal
                        is NotAuthenticated -> {
                            log.warn("Not authorized ${authresult.reason}")
                            call.respond(HttpStatusCode.Unauthorized)
                            return@post
                        }
                    }

                    val traceId = call.traceId()
                    val result = settKontorHandler.settKontor(kontorTilordning, principal, traceId)

                    when (result) {
                        is SettKontorFailure -> call.respond(result.statusCode, result.message)
                        is SettKontorSuccess -> call.respond(HttpStatusCode.OK, result.response)
                    }
                } catch (ex: Throwable) {
                    log.error("Kunne ikke sette kontor på bruker: ${ex.message}", ex)
                    call.respond(HttpStatusCode.InternalServerError, "Kunne ikke sette kontor på bruker: ${ex.message}")
                }
            }
        }
    }
}

@Serializable
data class Kontor(
    val kontorNavn: String,
    val kontorId: String,
)

@Serializable
data class ArbeidsoppfolgingsKontorTilordningDTO(
    val kontorId: String,
    val begrunnelse: String?,
    val ident: String
)

@Serializable
data class KontorByttetOkResponseDto(
    val fraKontor: Kontor?,
    val tilKontor: Kontor
)