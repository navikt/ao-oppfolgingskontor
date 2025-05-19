package no.nav.http.graphql.queries

import com.expediagroup.graphql.server.operations.Query
import graphql.schema.DataFetchingEnvironment
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
        return kontorTilhorighetService.getKontorTilhorighet(fnr)
    }

    suspend fun kontorTilhorigheter(fnr: Fnr, dataFetchingEnvironment: DataFetchingEnvironment): KontorTilhorigheterQueryDto {
        return KontorTilhorigheterQueryDto(
            arena = kontorTilhorighetService.getArenaKontorTilhorighet(fnr)?.toArenaKontorDto(),
            geografiskTilknytning = kontorTilhorighetService.getGeografiskTilknyttetKontorTilhorighet(fnr)?.toGeografiskTilknyttetKontorDto(),
            arbeidsoppfolging = kontorTilhorighetService.getArbeidsoppfolgingKontorTilhorighet(fnr)?.toArbeidsoppfolgingKontorDto(),
        )
    }
}
