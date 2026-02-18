package no.nav.http

import audit.Decision
import com.nimbusds.jose.util.DefaultResourceRetriever
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import no.nav.Authenticated
import no.nav.NavAnsatt
import no.nav.NotAuthenticated
import no.nav.audit.AuditLogger
import no.nav.audit.traceId
import no.nav.authenticateCall
import no.nav.db.Ident
import no.nav.db.IdentSomKanLagres
import no.nav.domain.OppfolgingsperiodeId
import no.nav.getIssuer
import no.nav.security.token.support.v3.RequiredClaims
import no.nav.security.token.support.v3.tokenValidationSupport
import no.nav.services.TilordningFeil
import no.nav.services.TilordningResultat
import no.nav.services.TilordningRetry
import no.nav.services.TilordningSuccessIngenEndring
import no.nav.services.TilordningSuccessKontorEndret
import org.slf4j.LoggerFactory
import services.ArenaSyncService
import services.KontorRepubliseringService
import java.util.UUID

val log = LoggerFactory.getLogger("AdminModule")


fun Application.configureAdminModule(
    simulerKontorTilordning: suspend (ident: IdentSomKanLagres, erArbeidssøker: Boolean) -> TilordningResultat,
    kontorRepubliseringService: KontorRepubliseringService,
    arenaSyncService: ArenaSyncService,
) {
    routing {
        val config = environment.config

        fun AuthenticationConfig.setUpAdminAuth() {
            tokenValidationSupport(
                config = config,
                requiredClaims = RequiredClaims(
                    issuer = config.configList("no.nav.security.jwt.issuers").first().property("issuer_name")
                        .getString(),
                    claimMap = arrayOf("scp=admin"),
                ),
                resourceRetriever = DefaultResourceRetriever(),
                name = "poaoAdmin"
            )
        }

        pluginOrNull(Authentication)?.configure { setUpAdminAuth() }
            ?: install(Authentication) { setUpAdminAuth() }

        authenticate("poaoAdmin") {
            post("/admin/republiser-arbeidsoppfolgingskontorendret") {
                runCatching {
                    log.info("Setter i gang async republisering av kontorer")
                    launch {
                        log.info("Starter republisering av kontorer...")
                        kontorRepubliseringService.republiserKontorer()
                        log.info("Fullført republisering av kontorer.")
                    }

                    call.respond(HttpStatusCode.Accepted, "Republisering startet")
                }.onFailure { e ->
                    log.error("Feil ved republisering av kontorer", e)

                    call.respond(
                        HttpStatusCode.InternalServerError,
                        "Klarte ikke starte republisering av kontorer: ${e.message} \n" + e.stackTraceToString()
                    )
                }
            }

            post("/admin/republiser-arbeidsoppfolgingskontorendret-utvalgte-perioder") {
                runCatching {
                    log.info("Setter i gang republisering av kontorer for utvalgte brukere")
                    val input = call.receive<OppfolgingsperiodeInputBody>()
                    val perioder = input.oppfolgingsperioder.split(",")
                        .map { OppfolgingsperiodeId(UUID.fromString(it)) }

                    kontorRepubliseringService.republiserKontorer(perioder)
                    call.respond(HttpStatusCode.Accepted, "Republisering av kontorer utvalgte brukere fullført.")
                }.onFailure { e ->
                    log.error("Feil ved republisering av kontorer for utvalgte brukere", e)

                    call.respond(
                        HttpStatusCode.InternalServerError,
                        "Klarte ikke starte republisering av kontorer for utvalgte brukere: ${e.message} \n" + e.stackTraceToString()
                    )
                }
            }

            post("/admin/sync-arena-kontor") {
                runCatching {
                    log.info("Setter i gang syncing av Arena-kontor")
                    val input = call.receive<IdenterInputBody>()
                    val identer = input.identer.split(",")
                    val godkjenteIdenter =
                        identer.map { Ident.validateIdentSomKanLagres(it, Ident.HistoriskStatus.UKJENT) }

                    log.info("Setter i gang sync av arena-kontor for ${godkjenteIdenter.size} identer av ${identer.size} mottatte identer")

                    arenaSyncService.refreshArenaKontor(godkjenteIdenter)
                    call.respond(HttpStatusCode.Accepted, "Syncing av Arena-kontorer startet")
                }.onFailure { e ->
                    log.error("Feil ved syncing av Arena-kontor", e)

                    call.respond(
                        HttpStatusCode.InternalServerError,
                        "Klarte ikke synce Arena-kontor: ${e.message} \n" + e.stackTraceToString()
                    )
                }
            }

            post("/admin/finn-kontor") {
                val principal = when (val authResult = call.authenticateCall(environment.getIssuer())) {
                    is Authenticated -> authResult.principal as? NavAnsatt
                    is NotAuthenticated -> {
                        log.warn("Not authorized ${authResult.reason}")
                        call.respond(HttpStatusCode.Unauthorized)
                        return@post
                    }
                } ?: throw IllegalStateException("Må være navansatt")

                val traceId = call.traceId()

                runCatching {
                    val bodyText = call.receiveText()
                    log.info("Setter i gang dry-run av kontor-ruting med input")
                    val input = Json.decodeFromString<IdenterInputBody>(bodyText)
                    val identer = input.identer.split(",")
                    val godkjenteIdenter =
                        identer.map { Ident.validateIdentSomKanLagres(it, Ident.HistoriskStatus.UKJENT) }
                    val result: Map<String, String> = godkjenteIdenter.associateWith { godkjentIdent ->
                        val res = simulerKontorTilordning(godkjentIdent, true)

                        AuditLogger.logAdminDryrunFinnKontor(
                            traceId = traceId,
                            principal = principal,
                            ident = godkjentIdent,
                            decision = Decision.Permit,
                        )

                        val output: String = when (res) {
                            is TilordningFeil -> res.message
                            is TilordningRetry -> res.message
                            TilordningSuccessIngenEndring -> "Ingen endring"
                            is TilordningSuccessKontorEndret -> """
                                FNR: ${godkjentIdent.value}
                                ao-kontor: ${res.kontorEndretEvent.aoKontorEndret?.tilordning?.kontorId?.id}
                                arenakontor: ${res.kontorEndretEvent.arenaKontorEndret?.tilordning?.kontorId?.id}
                                gt-kontor: ${res.kontorEndretEvent.gtKontorEndret?.tilordning?.kontorId?.id}
                            """.trimIndent()
                        }
                        output
                    }.mapKeys { it.key.value }
                    call.respond(
                        HttpStatusCode.Accepted,
                        result
                    )
                }.onFailure { e ->
                    log.error("Feil ved finn kontor", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        "Klarte ikke kjøre dry-run av kontor-ruting: ${e.message} \n" + e.stackTraceToString()
                    )
                }
            }
        }
    }
}

@Serializable
private data class IdenterInputBody(val identer: String)

@Serializable
private data class OppfolgingsperiodeInputBody(
    val oppfolgingsperioder: String // Kommeseparert liste over oppfolgingsperioder-id-er
)
