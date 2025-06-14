package no.nav.http.graphql.queries

import com.expediagroup.graphql.server.operations.Query
import graphql.schema.DataFetchingEnvironment
import no.nav.db.entity.KontorHistorikkEntity
import no.nav.db.table.KontorhistorikkTable
import no.nav.domain.KontorEndringsType
import no.nav.domain.getKilde
import no.nav.http.graphql.schemas.KontorHistorikkQueryDto
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

class KontorHistorikkQuery : Query {
    val logger = LoggerFactory.getLogger(KontorHistorikkQuery::class.java)

    fun kontorHistorikk(fnr: String, dataFetchingEnvironment: DataFetchingEnvironment): List<KontorHistorikkQueryDto> {
        return runCatching {
            transaction {
                KontorHistorikkEntity
                    .find { KontorhistorikkTable.fnr eq fnr }
                    .orderBy(KontorhistorikkTable.id to SortOrder.ASC)
                    .map {
                        val endringsType = KontorEndringsType.valueOf(it.kontorendringstype)
                        KontorHistorikkQueryDto(
                            kontorId = it.kontorId,
                            kilde = endringsType.getKilde(),
                            endretAv = it.endretAv,
                            endringsType = endringsType,
                            endretAvType = it.endretAvType,
                            endretTidspunkt = it.createdAt.toZonedDateTime().toString(),
                        )
                    }
            }
        }
            .onFailure {
                logger.error("Feil ved henting av kontorhistorikk")
                throw it
            }
            .onSuccess { return it }
            .getOrThrow()
    }
}
