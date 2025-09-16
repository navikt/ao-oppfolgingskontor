package no.nav.http.graphql.queries

import com.expediagroup.graphql.server.operations.Query
import graphql.schema.DataFetchingEnvironment
import no.nav.AOPrincipal
import no.nav.AuthResult
import no.nav.Authenticated
import no.nav.NotAuthenticated
import no.nav.db.Fnr
import no.nav.db.Ident
import no.nav.http.graphql.schemas.KontorTilhorighetQueryDto
import no.nav.http.graphql.schemas.KontorTilhorigheterQueryDto
import no.nav.http.graphql.schemas.toArbeidsoppfolgingKontorDto
import no.nav.http.graphql.schemas.toArenaKontorDto
import no.nav.http.graphql.schemas.toGeografiskTilknyttetKontorDto
import no.nav.services.KontorTilhorighetService
import org.slf4j.LoggerFactory

class KontorQuery(
    val kontorTilhorighetService: KontorTilhorighetService
) : Query {
    val log = LoggerFactory.getLogger(KontorQuery::class.java)

    suspend fun kontorTilhorighet(ident: String, dataFetchingEnvironment: DataFetchingEnvironment): KontorTilhorighetQueryDto? {
        val principal = dataFetchingEnvironment.graphQlContext.get<AOPrincipal>("principal")
        return kontorTilhorighetService.getKontorTilhorighet(Ident.of(ident, Ident.HistoriskStatus.UKJENT), principal)
    }

    suspend fun kontorTilhorigheter(ident: String, dataFetchingEnvironment: DataFetchingEnvironment): KontorTilhorigheterQueryDto {
        val principal = dataFetchingEnvironment.graphQlContext.get<AOPrincipal>("principal")
        val ident = Ident.of(ident, Ident.HistoriskStatus.UKJENT)
        val (arbeidsoppfolging, arena, gt) = kontorTilhorighetService.getKontorTilhorigheter(ident, principal)
        return KontorTilhorigheterQueryDto(
            arena = arena?.toArenaKontorDto(),
            geografiskTilknytning = gt?.toGeografiskTilknyttetKontorDto(),
            arbeidsoppfolging = arbeidsoppfolging?.toArbeidsoppfolgingKontorDto(),
        )
    }
}
