package no.nav.http.graphql.queries

import com.expediagroup.graphql.server.operations.Query
import graphql.schema.DataFetchingEnvironment
import no.nav.db.Fnr
import no.nav.db.table.ArbeidsOppfolgingKontorTable
import no.nav.domain.KontorKilde
import no.nav.http.graphql.schemas.KontorTilhorighetQueryDto
import no.nav.services.KontorTilhorighetService
import org.jetbrains.exposed.sql.*

val kontorAlias = ArbeidsOppfolgingKontorTable.kontorId.alias("kontorid")
val kontorkildeAlias = stringLiteral(KontorKilde.ARBEIDSOPPFOLGING.name).alias("kilde") // Tilfeldig valgt verdi
val prioritetAlias = intLiteral(0).alias("prioritet")

class KontorQuery(
    val kontorTilhorighetService: KontorTilhorighetService
) : Query {
    fun kontorTilhorighet(fnrParam: Fnr, dataFetchingEnvironment: DataFetchingEnvironment): KontorTilhorighetQueryDto? {
        return kontorTilhorighetService.getKontorTilhorighet(fnrParam)
    }
}
