package no.nav.http.graphql.queries

import com.expediagroup.graphql.server.operations.Query
import graphql.schema.DataFetchingEnvironment
import io.ktor.client.engine.callContext
import no.nav.AOPrincipal
import no.nav.db.Fnr
import no.nav.http.graphql.schemas.KontorTilhorighetQueryDto
import no.nav.http.graphql.schemas.KontorTilhorigheterQueryDto
import no.nav.http.graphql.schemas.toArbeidsoppfolgingKontorDto
import no.nav.http.graphql.schemas.toArenaKontorDto
import no.nav.http.graphql.schemas.toGeografiskTilknyttetKontorDto
import no.nav.services.KontorTilhorighetService

class KontorQuery(
    val kontorTilhorighetService: KontorTilhorighetService
) : Query {

    suspend fun kontorTilhorighet(fnr: Fnr, dataFetchingEnvironment: DataFetchingEnvironment): KontorTilhorighetQueryDto? {
        val principal = dataFetchingEnvironment.graphQlContext.get<AOPrincipal>("principal")
        return kontorTilhorighetService.getKontorTilhorighet(fnr, principal)
    }

    suspend fun kontorTilhorigheter(fnr: Fnr, dataFetchingEnvironment: DataFetchingEnvironment): KontorTilhorigheterQueryDto {
        val principal = dataFetchingEnvironment.graphQlContext.get<AOPrincipal>("principal")
        val (arbeidsoppfolging, arena, gt) = kontorTilhorighetService.getKontorTilhorigheter(fnr, principal)
        return KontorTilhorigheterQueryDto(
            arena = arena?.toArenaKontorDto(),
            geografiskTilknytning = gt?.toGeografiskTilknyttetKontorDto(),
            arbeidsoppfolging = arbeidsoppfolging?.toArbeidsoppfolgingKontorDto(),
        )
    }
}
