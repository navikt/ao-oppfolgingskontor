package no.nav.http.graphql.queries

import com.expediagroup.graphql.server.operations.Query
import io.ktor.util.toLowerCasePreservingASCIIRules
import no.nav.db.entity.ArenaKontorEntity
import no.nav.db.entity.GeografiskTilknyttetKontorEntity
import no.nav.db.table.ArenaKontorTable
import no.nav.db.table.GeografiskTilknytningKontorTable
import no.nav.http.client.NorgKontorType
import no.nav.http.client.MinimaltNorgKontor
import no.nav.http.client.Norg2Client
import no.nav.http.graphql.schemas.AlleKontorQueryDto
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

val velgbareNorgKontorTyper = listOf(
    NorgKontorType.LOKAL,
)

fun MinimaltNorgKontor.erEgenAnsattKontor() = this.navn.toLowerCasePreservingASCIIRules()
    .contains("egne ansatte")

fun erValgbartKontor(kontor: MinimaltNorgKontor): Boolean {
    if (kontor.type in velgbareNorgKontorTyper) return true
    if (kontor.erEgenAnsattKontor()) return true
    return false
}

class AlleKontorQuery(
    val norg2Client: Norg2Client
): Query {
    val logger = LoggerFactory.getLogger(AlleKontorQuery::class.java)

    suspend fun alleKontor(ident: String?): List<AlleKontorQueryDto> {
        return runCatching {
            val gtKontor = transaction {
                GeografiskTilknyttetKontorEntity.find { GeografiskTilknytningKontorTable.id eq ident }.firstOrNull()
            }
            val aoKontor = transaction {
                ArenaKontorEntity.find { ArenaKontorTable.id eq ident }.firstOrNull()
            }
            val skalSortereGtKontorFørAndreLokalkontor = gtKontor != null && (aoKontor == null || aoKontor.kontorId != gtKontor.kontorId)

            val spesialKontorerSomSkalSorteresFørst = listOf(
                AlleKontorQueryDto("4154","Nasjonal oppfølgingsenhet"),
                AlleKontorQueryDto("0393","Nav utland og fellestjenester Oslo"))

            val andreSpesialKontorer = listOf(
                AlleKontorQueryDto("2103","Nav Vikafossen"),
                AlleKontorQueryDto("2990","Nav IT-avdelingen")
            )

            val lokalKontorer = norg2Client.hentAlleEnheter()
                .filter { erValgbartKontor(it) }
                .map { AlleKontorQueryDto(it.kontorId,it.navn) }

            val kontorerSortertPåEnhetId = (lokalKontorer + andreSpesialKontorer)
                .sortedBy {
                    if (skalSortereGtKontorFørAndreLokalkontor && it.kontorId == gtKontor.kontorId) 0
                    else it.kontorId.toLong()
                }

            spesialKontorerSomSkalSorteresFørst + kontorerSortertPåEnhetId
        }
            .onSuccess { it }
            .onFailure {
                logger.error("Kunne ikke hente liste over kontor: ${it.cause} ${it.message}", it)
                throw it
            }
            .getOrThrow()
    }
}
