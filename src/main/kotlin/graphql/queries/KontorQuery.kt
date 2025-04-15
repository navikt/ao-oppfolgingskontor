package no.nav.graphql.queries

import com.expediagroup.graphql.server.operations.Query
import graphql.schema.DataFetchingEnvironment
import no.nav.db.Fnr
import no.nav.db.table.ArbeidsOppfolgingKontorTable
import no.nav.db.table.ArenaKontorTable
import no.nav.db.table.GeografiskTilknytningKontorTable
import no.nav.domain.KontorKilde
import no.nav.graphql.schemas.KontorQueryDto
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

val kontorAlias = ArbeidsOppfolgingKontorTable.kontorId.alias("kontorid")
val kontorkildeAlias = stringLiteral(KontorKilde.ARBEIDSOPPFOLGING.name).alias("kilde") // Tilfeldig valgt verdi
val prioritetAlias = intLiteral(0).alias("prioritet")

class KontorQuery : Query {
    fun kontorForBruker(fnrParam: Fnr, _: DataFetchingEnvironment): KontorQueryDto? {
        return transaction {

            val arbeidsoppfolgingKontorQuery = ArbeidsOppfolgingKontorTable.select(
                ArbeidsOppfolgingKontorTable.kontorId.alias(kontorAlias.alias),
                stringLiteral(KontorKilde.ARBEIDSOPPFOLGING.name).alias(kontorkildeAlias.alias),
                intLiteral(1).alias(prioritetAlias.alias)
            )
                .where { ArbeidsOppfolgingKontorTable.id eq fnrParam }

            val arenaKontorQuery = ArenaKontorTable.select(
                ArenaKontorTable.kontorId.alias(kontorAlias.alias),
                stringLiteral(KontorKilde.ARENA.name).alias(kontorkildeAlias.alias),
                intLiteral(2).alias(prioritetAlias.alias)
            )
                .where { ArenaKontorTable.id eq fnrParam }

            val geografiskTilknytningKontorQuery =
                GeografiskTilknytningKontorTable.select(
                    GeografiskTilknytningKontorTable.kontorId.alias(kontorAlias.alias),
                    stringLiteral(KontorKilde.GEOGRAFISK_TILKNYTNING.name).alias(kontorkildeAlias.alias),
                    intLiteral(3).alias(prioritetAlias.alias)
                )
                    .where(GeografiskTilknytningKontorTable.id eq fnrParam)

            val resultRow = arbeidsoppfolgingKontorQuery
                .unionAll(arenaKontorQuery)
                .unionAll(geografiskTilknytningKontorQuery)
                .orderBy(prioritetAlias to SortOrder.ASC)
                .limit(1)
                .firstOrNull()

            resultRow?.let { row -> KontorQueryDto(row[kontorAlias], KontorKilde.valueOf(row[kontorkildeAlias])) }
        }
    }
}
