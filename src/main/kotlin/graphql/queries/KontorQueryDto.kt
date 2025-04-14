package no.nav.graphql.queries

import com.expediagroup.graphql.server.operations.Query
import no.nav.graphql.schemas.KontorQueryDto

class KontorQuery: Query {
    fun kontorForBruker(/*params: NoParams, dataFetchingEnvironment: DataFetchingEnvironment*/): KontorQueryDto? {
        return null
    }
}

class NoParams()