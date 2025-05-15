package no.nav.services

import no.nav.db.Fnr
import no.nav.db.dto.ArbeidsoppfolgingDBKontor
import no.nav.db.entity.ArbeidsOppfolgingKontorEntity
import no.nav.db.table.ArbeidsOppfolgingKontorTable
import no.nav.db.table.ArenaKontorTable
import no.nav.db.table.GeografiskTilknytningKontorTable
import no.nav.domain.ArbeidsoppfolgingsKontor
import no.nav.domain.KontorId
import no.nav.domain.KontorKilde
import no.nav.domain.KontorNavn
import no.nav.http.graphql.queries.kontorAlias
import no.nav.http.graphql.queries.kontorkildeAlias
import no.nav.http.graphql.queries.prioritetAlias
import no.nav.http.graphql.schemas.KontorTilhorighetQueryDto
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.intLiteral
import org.jetbrains.exposed.sql.stringLiteral
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.unionAll

class KontorTilhorighetService(
    val kontorNavnService: KontorNavnService
) {
    suspend fun getArbeidsoppfolgingKontorTilhorighet(fnr: Fnr): ArbeidsoppfolgingsKontor? {
        return newSuspendedTransaction {
            ArbeidsOppfolgingKontorEntity.findById(fnr)
                ?.let { kontorNavnService.getKontorNavn(KontorId(it.kontorId)) }
                ?.let {
                    ArbeidsoppfolgingsKontor(
                        it.kontorNavn,
                        it.kontorId,
                    )
                }
        }
    }

    fun getKontorTilhorighet(fnr: Fnr): KontorTilhorighetQueryDto? {
        return transaction {

            val arbeidsoppfolgingKontorQuery = ArbeidsOppfolgingKontorTable.select(
                ArbeidsOppfolgingKontorTable.kontorId.alias(kontorAlias.alias),
                stringLiteral(KontorKilde.ARBEIDSOPPFOLGING.name).alias(kontorkildeAlias.alias),
                intLiteral(1).alias(prioritetAlias.alias)
            )
                .where { ArbeidsOppfolgingKontorTable.id eq fnr }

            val arenaKontorQuery = ArenaKontorTable.select(
                ArenaKontorTable.kontorId.alias(kontorAlias.alias),
                stringLiteral(KontorKilde.ARENA.name).alias(kontorkildeAlias.alias),
                intLiteral(2).alias(prioritetAlias.alias)
            )
                .where { ArenaKontorTable.id eq fnr }

            val geografiskTilknytningKontorQuery =
                GeografiskTilknytningKontorTable.select(
                    GeografiskTilknytningKontorTable.kontorId.alias(kontorAlias.alias),
                    stringLiteral(KontorKilde.GEOGRAFISK_TILKNYTNING.name).alias(kontorkildeAlias.alias),
                    intLiteral(3).alias(prioritetAlias.alias)
                )
                    .where(GeografiskTilknytningKontorTable.id eq fnr)

            val resultRow = arbeidsoppfolgingKontorQuery
                .unionAll(arenaKontorQuery)
                .unionAll(geografiskTilknytningKontorQuery)
                .orderBy(prioritetAlias to SortOrder.ASC)
                .limit(1)
                .firstOrNull()

            resultRow?.let { row ->
                KontorTilhorighetQueryDto(
                row[kontorAlias],
                KontorKilde.valueOf(row[kontorkildeAlias])
                )
            }
        }
    }
}