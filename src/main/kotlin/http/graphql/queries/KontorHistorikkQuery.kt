package no.nav.http.graphql.queries

import com.expediagroup.graphql.server.operations.Query
import graphql.schema.DataFetchingEnvironment
import io.ktor.server.application.*
import kotlinx.coroutines.runBlocking
import no.nav.AOPrincipal
import no.nav.audit.AuditLogger
import no.nav.audit.Decision
import no.nav.db.Ident
import no.nav.db.table.KontorNavnTable
import no.nav.db.table.KontorNavnTable.kontorNavn
import no.nav.db.table.KontorhistorikkTable
import no.nav.db.table.KontorhistorikkTable.createdAt
import no.nav.db.table.KontorhistorikkTable.endretAv
import no.nav.db.table.KontorhistorikkTable.endretAvType
import no.nav.db.table.KontorhistorikkTable.kontorId
import no.nav.db.table.KontorhistorikkTable.kontorType
import no.nav.domain.KontorEndringsType
import no.nav.domain.KontorType
import no.nav.http.client.IdenterResult
import no.nav.http.graphql.schemas.KontorHistorikkQueryDto
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

class KontorHistorikkQuery(
    val hentAlleIdenter: suspend (Ident) -> IdenterResult
) : Query {
    val logger = LoggerFactory.getLogger(KontorHistorikkQuery::class.java)

    fun kontorHistorikk(ident: String, dataFetchingEnvironment: DataFetchingEnvironment): List<KontorHistorikkQueryDto> {
        val principal = dataFetchingEnvironment.graphQlContext.get<AOPrincipal>("principal")
        val traceId = dataFetchingEnvironment.graphQlContext.get<String>("traceId")
            ?: throw IllegalStateException("Missing traceparent header")

        runCatching {
            val inputIdent = Ident.validateOrThrow(ident, Ident.HistoriskStatus.UKJENT)
            transaction {
                val alleIdenter = runBlocking { hentAlleIdenter(inputIdent).getOrThrow() }
                KontorhistorikkTable
                    .join(
                        KontorNavnTable,
                        JoinType.LEFT,
                        onColumn = kontorId,
                        otherColumn = KontorNavnTable.id,
                    )
                    .select(
                        KontorhistorikkTable.ident,
                        KontorhistorikkTable.kontorendringstype,
                        endretAv,
                        endretAvType,
                        createdAt,
                        kontorNavn,
                        kontorId,
                        kontorType
                    )
                    .where { KontorhistorikkTable.ident inList alleIdenter.identer.map { it.value } }
                    .orderBy(createdAt to SortOrder.DESC)
                    .map {
                        val endringsType = KontorEndringsType.valueOf(it[KontorhistorikkTable.kontorendringstype])
                        KontorHistorikkQueryDto(
                            ident = it[KontorhistorikkTable.ident],
                            kontorId = it[kontorId],
                            kontorType = KontorType.valueOf(it[kontorType]),
                            endretAv = it[endretAv],
                            endringsType = endringsType,
                            endretAvType = it[endretAvType],
                            endretTidspunkt = it[createdAt].toZonedDateTime().toString(),
                            kontorNavn = it[kontorNavn]
                        )
                    }
            } to inputIdent
        }
            .fold(
                { (historikk, inputIdent) ->
                    AuditLogger.logLesKontorhistorikk(
                        traceId = traceId,
                        principal = principal,
                        duid = inputIdent,
                        decision = Decision.PERMIT
                    )
                    return historikk
                },
                {
                    logger.error("Feil ved henting av kontorhistorikk", it)
                    throw it
                }
            )
    }
}
