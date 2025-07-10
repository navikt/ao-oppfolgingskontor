package no.nav.http

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.*
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import no.nav.Authenticated
import no.nav.NotAuthenticated
import no.nav.authenticateCall
import no.nav.db.Fnr
import no.nav.domain.KontorId
import no.nav.domain.KontorTilordning
import no.nav.domain.events.KontorSattAvVeileder
import no.nav.getIssuer
import no.nav.http.client.FnrFunnet
import no.nav.http.client.poaoTilgang.HarIkkeTilgang
import no.nav.http.client.poaoTilgang.HarTilgang
import no.nav.http.client.poaoTilgang.PoaoTilgangKtorHttpClient
import no.nav.http.client.poaoTilgang.TilgangOppslagFeil
import no.nav.http.graphql.AuthenticateRequest
import no.nav.services.AktivOppfolgingsperiode
import no.nav.services.KontorNavnService
import no.nav.services.KontorTilhorighetService
import no.nav.services.KontorTilordningService
import no.nav.services.NotUnderOppfolging
import no.nav.services.OppfolgingperiodeOppslagFeil
import no.nav.services.OppfolgingsperiodeService
import no.nav.toRegistrant
import org.slf4j.LoggerFactory

val logger = LoggerFactory.getLogger("Applcation.configureArbeidsoppfolgingskontorModule")

fun Application.configureArbeidsoppfolgingskontorModule(
    kontorNavnService: KontorNavnService,
    kontorTilhorighetService: KontorTilhorighetService,
    poaoTilgangClient: PoaoTilgangKtorHttpClient,
    authenticateRequest: AuthenticateRequest = { req -> req.call.authenticateCall(environment.getIssuer()) }
) {
    val log = LoggerFactory.getLogger("Applcation.configureArbeidsoppfolgingskontorModule")

    routing {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            })
        }
        authenticate {
            post("/api/kontor") {
                runCatching {
                    val kontorTilordning = call.receive<ArbeidsoppfolgingsKontorTilordningDTO>()
                    val principal = when(val authresult = authenticateRequest(call.request)) {
                        is Authenticated -> authresult.principal
                        is NotAuthenticated -> {
                            log.warn("Not authorized ${authresult.reason}")
                            call.respond(HttpStatusCode.Unauthorized)
                            return@post
                        }
                    }

                    val harTilgang = poaoTilgangClient.harLeseTilgang(principal, Fnr(kontorTilordning.fnr))
                    when (harTilgang) {
                        is HarIkkeTilgang -> {
                            logger.warn("Bruker/system har ikke tilgang til å endre kontor for bruker")
                            call.respond(HttpStatusCode.Forbidden, "Du har ikke tilgang til å endre kontor for denne brukeren")
                            return@post
                        }
                        HarTilgang -> {}
                        is TilgangOppslagFeil -> {
                            logger.warn(harTilgang.message)
                            call.respond(HttpStatusCode.InternalServerError, "Noe gikk galt under oppslag av tilgang for bruker")
                            return@post
                        }
                    }
                    val gammeltKontor = kontorTilhorighetService.getArbeidsoppfolgingKontorTilhorighet(Fnr(kontorTilordning.fnr), principal)
                    val kontorId = KontorId(kontorTilordning.kontorId)

                    val fnr = Fnr(kontorTilordning.fnr)
                    val oppfolgingsperiode = OppfolgingsperiodeService.getCurrentOppfolgingsperiode(FnrFunnet(fnr))
                    val oppfolgingsperiodeId = when(oppfolgingsperiode) {
                        is AktivOppfolgingsperiode -> oppfolgingsperiode.periodeId
                        NotUnderOppfolging -> {
                            call.respond(HttpStatusCode.Conflict, "Bruker er ikke under oppfølging")
                            return@post
                        }
                        is OppfolgingperiodeOppslagFeil -> {
                            log.error("Klarte ikke hente oppfølgingsperiode: ${oppfolgingsperiode.message}")
                            call.respond(HttpStatusCode.InternalServerError, "Klarte ikke hente oppfølgingsperiode")
                            return@post
                        }
                    }

                    KontorTilordningService.tilordneKontor(
                        KontorSattAvVeileder(
                            tilhorighet = KontorTilordning(
                                fnr = fnr,
                                kontorId = kontorId,
                                oppfolgingsperiodeId
                            ),
                            registrant = principal.toRegistrant()
                        )
                    )
                    kontorId to gammeltKontor
                }
                    .onSuccess { (kontorId, gammeltKontor) ->
                        val kontorNavn = kontorNavnService.getKontorNavn(kontorId)
                        call.respond(KontorByttetOkResponseDto(
                            fraKontor = gammeltKontor?.let {
                                Kontor(
                                    kontorNavn = it.kontorNavn.navn,
                                    kontorId = it.kontorId.id,
                                )
                            },
                            tilKontor = Kontor(
                                kontorNavn = kontorNavn.navn,
                                kontorId = kontorId.id
                            )
                        ))
                        call.respondText("OK", status = HttpStatusCode.OK)
                    }
                    .onFailure {
                        logger.error("Kunne ikke oppdatere kontor", it)
                        call.respondText( "Kunne ikke oppdatere kontor", status = HttpStatusCode.InternalServerError)
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
    val fnr: String
)

@Serializable
data class KontorByttetOkResponseDto(
    val fraKontor: Kontor?,
    val tilKontor: Kontor
)