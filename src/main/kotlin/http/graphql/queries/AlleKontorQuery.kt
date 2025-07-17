package no.nav.http.graphql.queries

import com.expediagroup.graphql.server.operations.Query
import no.nav.http.client.Norg2Client
import no.nav.http.graphql.schemas.AlleKontorQueryDto
import org.slf4j.LoggerFactory

class AlleKontorQuery(
    val norg2Client: Norg2Client
): Query {
    val logger = LoggerFactory.getLogger(AlleKontorQuery::class.java)

    suspend fun alleKontor(): List<AlleKontorQueryDto> {
        return runCatching {
            val lokalKontor = norg2Client.hentAlleEnheter()
                .map { AlleKontorQueryDto(it.kontorId,it.navn) }
            listOf(
                AlleKontorQueryDto("4154","Nasjonal oppfølgingsenhet"),
                AlleKontorQueryDto("0393","Nav utland og fellestjenester Oslo"),
                AlleKontorQueryDto("2103","Nav Vikafossen"),
            ) + lokalKontor
        }
            .onSuccess { it }
            .onFailure {
                logger.error("Kunne ikke hent liste over kontor: ${it.cause} ${it.message}", it)
                throw it
            }
            .getOrThrow()
    }
}
