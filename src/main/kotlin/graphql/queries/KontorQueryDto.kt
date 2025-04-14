package no.nav.graphql.queries

import com.expediagroup.graphql.server.operations.Query
import graphql.schema.DataFetchingEnvironment
import no.nav.db.entity.ArenaKontorEntity
import no.nav.graphql.schemas.KontorQueryDto

class KontorQuery: Query {
    fun kontorForBruker(params: FnrParam, dataFetchingEnvironment: DataFetchingEnvironment): KontorQueryDto? {
        return ArenaKontorEntity.findById(params.fnr)
            ?.let { KontorQueryDto(
                kontorId = it.kontorId,
            ) }
    }
}

class FnrParam(
    val fnr: String,
)
