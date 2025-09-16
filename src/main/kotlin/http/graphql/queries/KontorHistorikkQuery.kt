package no.nav.http.graphql.queries

import com.expediagroup.graphql.server.operations.Query
import graphql.schema.DataFetchingEnvironment
import no.nav.db.Ident
import no.nav.db.entity.KontorHistorikkEntity
import no.nav.db.table.KontorhistorikkTable
import no.nav.domain.KontorEndringsType
import no.nav.domain.KontorType
import no.nav.http.graphql.schemas.KontorHistorikkQueryDto
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import services.IdentService

class KontorHistorikkQuery(
    val identService: IdentService
) : Query {
    val logger = LoggerFactory.getLogger(KontorHistorikkQuery::class.java)

    fun kontorHistorikk(ident: String, dataFetchingEnvironment: DataFetchingEnvironment): List<KontorHistorikkQueryDto> {
        return runCatching {
            transaction {
                // TODO Flytt dette til en service??
                val inputIdent = Ident.of(ident, Ident.HistoriskStatus.UKJENT)
                val alleIdenter = identService.hentAlleIdenter(inputIdent).getOrThrow()
                KontorHistorikkEntity
                    .find { KontorhistorikkTable.ident inList alleIdenter.identer.map { it.value } }
                    .orderBy(KontorhistorikkTable.id to SortOrder.ASC)
                    .map {
                        val endringsType = KontorEndringsType.valueOf(it.kontorendringstype)
                        KontorHistorikkQueryDto(
                            kontorId = it.kontorId,
                            kontorType = KontorType.valueOf(it.kontorType),
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
