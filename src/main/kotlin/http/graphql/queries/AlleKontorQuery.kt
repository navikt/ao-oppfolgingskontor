package no.nav.http.graphql.queries

import com.expediagroup.graphql.server.operations.Query
import no.nav.http.client.Norg2Client
import no.nav.http.graphql.schemas.AlleKontorQueryDto

class AlleKontorQuery(
    baseUrl: String
): Query {
    val norg2Client = Norg2Client(baseUrl)

    suspend fun alleKontor(): List<AlleKontorQueryDto> {
        return norg2Client.hentAlleEnheter()
            .map {
                AlleKontorQueryDto(
                    it.kontorId,
                    it.navn
                )
            }
    }
}
