package no.nav.http.graphql.queries

import com.expediagroup.graphql.server.operations.Query
import graphql.schema.DataFetchingEnvironment
import no.nav.AOPrincipal
import no.nav.db.Ident
import no.nav.http.client.IdenterResult
import no.nav.http.client.poaoTilgang.HarIkkeTilgang
import no.nav.http.client.poaoTilgang.HarTilgang
import no.nav.http.client.poaoTilgang.TilgangOppslagFeil
import no.nav.http.client.poaoTilgang.TilgangResult
import no.nav.http.graphql.schemas.KontorTilhorighetQueryDto
import no.nav.http.graphql.schemas.KontorTilhorigheterQueryDto
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
        val ident = Ident.validateOrThrow(ident, Ident.HistoriskStatus.UKJENT)
        val identer = hentAlleIdenter(ident).getOrThrow()
        val result = harLeseTilgang(principal, identer.foretrukketIdent)
        if (result is HarIkkeTilgang) throw Exception("Bruker har ikke lov å lese kontortilhørighet på denne brukeren")
        if (result is TilgangOppslagFeil) throw Exception("Klarte ikke sjekke om nav-ansatt har tilgang til bruker: ${result.message}")
        return kontorTilhorighetService.getKontorTilhorighet(identer)
    }

    suspend fun kontorTilhorigheter(ident: String, dataFetchingEnvironment: DataFetchingEnvironment): KontorTilhorigheterQueryDto {
        val principal = dataFetchingEnvironment.graphQlContext.get<AOPrincipal>("principal")
        val ident = Ident.validateOrThrow(ident, Ident.HistoriskStatus.UKJENT)
        val identer = hentAlleIdenter(ident).getOrThrow()
        val result = harLeseTilgang(principal, ident)
        if (result is HarIkkeTilgang) throw Exception("Bruker har ikke lov å lese kontortilhørigheter på denne brukeren")
        if (result is TilgangOppslagFeil) throw Exception("Klarte ikke sjekke om nav-ansatt har tilgang til bruker: ${result.message}")
        val (arbeidsoppfolging, arena, gt) = kontorTilhorighetService.getKontorTilhorigheter(identer)
        return KontorTilhorigheterQueryDto(
            arena = arena?.toArenaKontorDto(),
            geografiskTilknytning = gt?.toGeografiskTilknyttetKontorDto(),
            arbeidsoppfolging = arbeidsoppfolging?.toArbeidsoppfolgingKontorDto(),
        )
    }
}
