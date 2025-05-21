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
import no.nav.db.Fnr
import no.nav.db.table.ArbeidsOppfolgingKontorTable
import no.nav.db.table.KontorhistorikkTable.fnr
import no.nav.domain.KontorId
import no.nav.security.token.support.v3.TokenValidationContextPrincipal
import no.nav.services.KontorNavnService
import no.nav.services.KontorTilhorighetService
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert
import org.slf4j.LoggerFactory

val logger = LoggerFactory.getLogger("Applcation.configureArbeidsoppfolgingskontorModule")

fun Application.configureArbeidsoppfolgingskontorModule(
    kontorNavnService: KontorNavnService,
    kontorTilhorighetService: KontorTilhorighetService
) {
    val issuer = environment.config.property("auth.entraIssuer").getString()

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
                    val kontor = call.receive<ArbeidsoppfolgingsKontorTilordningDTO>()
                    val principal = call.principal<TokenValidationContextPrincipal>()
                    val veilederIdent = principal?.context?.getClaims(issuer)?.getStringClaim("NAVident")
                        ?: throw IllegalStateException("NAVident not found in token")
                    val gammeltKontor = kontorTilhorighetService.getArbeidsoppfolgingKontorTilhorighet(kontor.fnr)

                    transaction {
                        ArbeidsOppfolgingKontorTable.upsert {
                            it[kontorId] = kontor.kontorId
                            it[fnr] = kontor.fnr
                            it[endretAv] = veilederIdent
                            it[endretAvType] = "VEILEDER"
                        }
                    }.let { KontorId(it[ArbeidsOppfolgingKontorTable.kontorId]) to gammeltKontor }
                }
                    .onSuccess { (kontorId, gammeltKontor) ->
                        val kontorNavn = kontorNavnService.getKontorNavn(kontorId)
                        call.respond(KontorByttetOkResponseDto(
                            fraKontor = gammeltKontor?.let {
                                Kontor(
                                    kontorNavn = it.kontorNavn.navn,
                                    kontorId = it.kontorId.id
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
    val fnr: Fnr
)

@Serializable
data class KontorByttetOkResponseDto(
    val fraKontor: Kontor?,
    val tilKontor: Kontor
)