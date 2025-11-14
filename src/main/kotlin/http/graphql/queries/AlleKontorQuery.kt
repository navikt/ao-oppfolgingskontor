package no.nav.http.graphql.queries

import com.expediagroup.graphql.server.operations.Query
import io.ktor.util.toLowerCasePreservingASCIIRules
import no.nav.db.entity.GeografiskTilknyttetKontorEntity
import no.nav.db.table.GeografiskTilknytningKontorTable
import no.nav.http.client.KontorType
import no.nav.http.client.MinimaltNorgKontor
import no.nav.http.client.Norg2Client
import no.nav.http.graphql.schemas.AlleKontorQueryDto
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

val velgbareKontorTyper = listOf(
    KontorType.LOKAL,
)

fun MinimaltNorgKontor.erEgenAnsattKontor() = this.navn.toLowerCasePreservingASCIIRules()
    .contains("egne ansatte")

fun erValgbartKontor(kontor: MinimaltNorgKontor): Boolean {
    if (kontor.type in velgbareKontorTyper) return true
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

            fun compareKontor(kontor: MinimaltNorgKontor, otherKontor: MinimaltNorgKontor): Int {
                return when {
                    kontor == gtKontor -> -1
                    else -> 0
                }
            }
            val spesialKontorerSomSkalSorteresFørst = listOf(
                AlleKontorQueryDto("4154","Nasjonal oppfølgingsenhet"),
                AlleKontorQueryDto("0393","Nav utland og fellestjenester Oslo"))

            val andreSpesialKontorer = listOf(
                AlleKontorQueryDto("2103","Nav Vikafossen"),
                AlleKontorQueryDto("2990","Nav IT-avdelingen")
            )

            val lokalKontor = norg2Client.hentAlleEnheter()
                .filter { erValgbartKontor(it) }
                .map { AlleKontorQueryDto(it.kontorId,it.navn) }

            val kontorerSortertPåEnhetId = (lokalKontor + andreSpesialKontorer)
                .sortedBy { it.kontorId }

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
