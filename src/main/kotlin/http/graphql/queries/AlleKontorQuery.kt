package no.nav.http.graphql.queries

import com.expediagroup.graphql.server.operations.Query
import no.nav.db.entity.GeografiskTilknyttetKontorEntity
import no.nav.db.table.GeografiskTilknytningKontorTable
import no.nav.http.client.KontorType
import no.nav.http.client.Norg2Client
import no.nav.http.graphql.schemas.AlleKontorQueryDto
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

class AlleKontorQuery(
    val norg2Client: Norg2Client
): Query {
    val logger = LoggerFactory.getLogger(AlleKontorQuery::class.java)

    suspend fun alleKontor(ident: String?): List<AlleKontorQueryDto> {
        return runCatching {
            val gtKontor = transaction {
                GeografiskTilknyttetKontorEntity.find { GeografiskTilknytningKontorTable.id eq ident }.firstOrNull()
            }
            val alleKontor = norg2Client.hentAlleEnheter()
                .let {
                    if (gtKontor == null) { it }
                    else {
                        it.sortedWith { o1, o2 -> if (o1.kontorId == gtKontor.kontorId) -1 else 1 }
                    }
                }
                .let { it.sortedWith { o1, o2 ->  if (o1.type == KontorType.KO) -1 else 1 } }
                .map { AlleKontorQueryDto(it.kontorId,it.navn) }
//            listOf(
//                AlleKontorQueryDto("4154","Nasjonal oppfølgingsenhet"),
//                AlleKontorQueryDto("0393","Nav utland og fellestjenester Oslo"),
//                AlleKontorQueryDto("2103","Nav Vikafossen"),
//                AlleKontorQueryDto("2990","Nav IT-avdelingen"),
//            ) + lokalKontor
            alleKontor
        }
            .onSuccess { it }
            .onFailure {
                logger.error("Kunne ikke hent liste over kontor: ${it.cause} ${it.message}", it)
                throw it
            }
            .getOrThrow()
    }
}
