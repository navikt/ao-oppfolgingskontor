package no.nav.http.graphql.queries

import com.expediagroup.graphql.server.operations.Query
import graphql.schema.DataFetchingEnvironment
import no.nav.db.Fnr
import no.nav.db.table.ArbeidsOppfolgingKontorTable
import no.nav.domain.KontorKilde
import no.nav.http.graphql.schemas.KontorTilhorighetQueryDto
import no.nav.services.KontorTilhorighetService
import org.jetbrains.exposed.sql.*

class KontorQuery(
    val kontorTilhorighetService: KontorTilhorighetService
) : Query {
    suspend fun kontorTilhorighet(fnrParam: Fnr, dataFetchingEnvironment: DataFetchingEnvironment): KontorTilhorighetQueryDto? {
        return kontorTilhorighetService.getKontorTilhorighet(fnrParam)
    }
}
