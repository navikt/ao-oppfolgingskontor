package no.nav.http.graphql.queries

import com.expediagroup.graphql.server.operations.Query
import graphql.schema.DataFetchingEnvironment
import no.nav.AOPrincipal
import no.nav.audit.AuditEntry
import no.nav.audit.AuditLogger
import no.nav.audit.Decision
import no.nav.audit.toAuditEntry
import no.nav.db.Ident
import no.nav.db.entity.ArbeidsOppfolgingKontorEntity
import no.nav.db.entity.ArenaKontorEntity
import no.nav.db.entity.GeografiskTilknyttetKontorEntity
import no.nav.domain.KontorNavn
import no.nav.domain.KontorType
import no.nav.http.client.IdenterResult
import no.nav.http.client.poaoTilgang.HarIkkeTilgang
import no.nav.http.client.poaoTilgang.TilgangOppslagFeil
import no.nav.http.client.poaoTilgang.TilgangResult
import no.nav.http.graphql.schemas.KontorTilhorighetQueryDto
import no.nav.http.graphql.schemas.KontorTilhorigheterQueryDto
import no.nav.http.graphql.schemas.RegistrantTypeDto
import no.nav.http.graphql.schemas.toArbeidsoppfolgingKontorDto
import no.nav.http.graphql.schemas.toArenaKontorDto
import no.nav.http.graphql.schemas.toGeografiskTilknyttetKontorDto
import no.nav.services.KontorTilhorighetService
import org.slf4j.LoggerFactory

class KontorQuery(
    val kontorTilhorighetService: KontorTilhorighetService,
    val harLeseTilgang: suspend (principal: AOPrincipal, ident: Ident) -> TilgangResult,
    val hentAlleIdenter: suspend (Ident) -> IdenterResult,
) : Query {
    val log = LoggerFactory.getLogger(KontorQuery::class.java)

    suspend fun kontorTilhorighet(ident: String, dataFetchingEnvironment: DataFetchingEnvironment): KontorTilhorighetQueryDto? {
        val principal = dataFetchingEnvironment.graphQlContext.get<AOPrincipal>("principal")
        val traceId = dataFetchingEnvironment.graphQlContext.get<String>("traceId")
            ?: throw IllegalStateException("Missing traceparent header")

        val ident = Ident.validateOrThrow(ident, Ident.HistoriskStatus.UKJENT)
        val identer = hentAlleIdenter(ident).getOrThrow()
        val result = harLeseTilgang(principal, identer.foretrukketIdent)
        if (result is HarIkkeTilgang) {
            throw Exception("Bruker har ikke lov å lese kontortilhørighet på denne brukeren")
        }
        if (result is TilgangOppslagFeil) {
            throw Exception("Klarte ikke sjekke om nav-ansatt har tilgang til å lese kontortilhørighet på bruker: ${result.message}")
        }

        val kontorTilhorighet = kontorTilhorighetService.getKontorTilhorighet(identer)

        AuditLogger.logLesKontortilhorighet(AuditEntry(
            traceId = traceId,
            principal = principal,
            duid = ident,
            decision = Decision.PERMIT
        ))

        return kontorTilhorighet
    }

    suspend fun kontorTilhorigheter(ident: String, dataFetchingEnvironment: DataFetchingEnvironment): KontorTilhorigheterQueryDto {
        val principal = dataFetchingEnvironment.graphQlContext.get<AOPrincipal>("principal")
        val traceId = dataFetchingEnvironment.graphQlContext.get<String>("traceId")

        val ident = Ident.validateOrThrow(ident, Ident.HistoriskStatus.UKJENT)
        val identer = hentAlleIdenter(ident).getOrThrow()
        val result = harLeseTilgang(principal, ident)
        if (result is HarIkkeTilgang) {
            AuditLogger.logLesKontortilhorighet(result.toAuditEntry(traceId))
            throw Exception("Bruker har ikke lov å lese kontortilhørigheter på denne brukeren")
        }
        if (result is TilgangOppslagFeil) {
            throw Exception("Klarte ikke sjekke om nav-ansatt har tilgang til å lese kontortilhørigheter på bruker: ${result.message}")
        }

        val (arbeidsoppfolging, arena, gt) = kontorTilhorighetService.getKontorTilhorigheter(identer)

        AuditLogger.logLesKontortilhorighet(AuditEntry(traceId, principal, ident, Decision.PERMIT))

        return KontorTilhorigheterQueryDto(
            arena = arena?.toArenaKontorDto(),
            geografiskTilknytning = gt?.toGeografiskTilknyttetKontorDto(),
            arbeidsoppfolging = arbeidsoppfolging?.toArbeidsoppfolgingKontorDto(),
        )
    }
}

fun ArbeidsOppfolgingKontorEntity.toKontorTilhorighetQueryDto(navn: KontorNavn): KontorTilhorighetQueryDto {
    return KontorTilhorighetQueryDto(
        kontorId = this.kontorId,
        kontorType = KontorType.ARBEIDSOPPFOLGING,
        registrant = this.endretAv,
        registrantType = RegistrantTypeDto.valueOf(this.endretAvType),
        kontorNavn = navn.navn
    )
}
fun ArenaKontorEntity.toKontorTilhorighetQueryDto(navn: KontorNavn): KontorTilhorighetQueryDto {
    return KontorTilhorighetQueryDto(
        kontorId = this.kontorId,
        kontorType = KontorType.ARENA,
        registrant = "Arena",
        registrantType = RegistrantTypeDto.ARENA,
        kontorNavn = navn.navn
    )
}
fun GeografiskTilknyttetKontorEntity.toKontorTilhorighetQueryDto(navn: KontorNavn): KontorTilhorighetQueryDto {
    return KontorTilhorighetQueryDto(
        kontorId = this.kontorId,
        kontorType = KontorType.GEOGRAFISK_TILKNYTNING,
        registrant = "FREG",
        registrantType = RegistrantTypeDto.SYSTEM,
        kontorNavn = navn.navn
    )
}