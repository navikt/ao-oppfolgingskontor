package no.nav.graphql.queries

import com.expediagroup.graphql.server.operations.Query
import graphql.schema.DataFetchingEnvironment
import no.nav.db.entity.ArenaKontorEntity
import no.nav.graphql.schemas.KontorQueryDto
import org.jetbrains.exposed.sql.transactions.transaction

class KontorQuery: Query {
    fun kontorForBruker(fnrParam: String, dataFetchingEnvironment: DataFetchingEnvironment): KontorQueryDto? {
        return transaction {
            ArenaKontorEntity.findById(fnrParam)
                ?.let { KontorQueryDto(
                    kontorId = it.kontorId,
                ) }
        }
    }
}

class FnrParam(
    val fnr: String,
)
