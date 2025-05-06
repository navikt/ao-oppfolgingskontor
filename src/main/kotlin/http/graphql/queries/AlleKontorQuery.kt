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
            norg2Client.hentAlleEnheter()
                .map {
                    AlleKontorQueryDto(
                        it.kontorId,
                        it.navn
                    )
                }
        }
            .onSuccess { it }
            .onFailure {
                logger.error("Kunne ikke hent liste over kontor.", it)
                throw it
            }
            .getOrThrow()
    }
}
