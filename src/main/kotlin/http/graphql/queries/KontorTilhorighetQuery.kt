package no.nav.http.graphql.queries

import com.expediagroup.graphql.server.operations.Query
import graphql.schema.DataFetchingEnvironment
import no.nav.db.Fnr
import no.nav.http.graphql.schemas.KontorTilhorighetQueryDto
import no.nav.services.KontorTilhorighetService

class KontorQuery(
    val kontorTilhorighetService: KontorTilhorighetService
) : Query {
    suspend fun kontorTilhorighet(fnr: Fnr, dataFetchingEnvironment: DataFetchingEnvironment): KontorTilhorighetQueryDto? {
        return kontorTilhorighetService.getKontorTilhorighet(fnr)
    }
}
