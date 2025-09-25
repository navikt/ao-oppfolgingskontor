package no.nav.http.client

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import no.nav.db.Fnr
import no.nav.domain.NavIdent
import no.nav.security.mock.oauth2.MockOAuth2Server

suspend fun HttpClient.settKontor(server: MockOAuth2Server, kontorId: String, fnr: Fnr, navIdent: NavIdent): HttpResponse {
    return post("/api/kontor") {
        header("Authorization", "Bearer ${server.issueToken(
            claims = mapOf(
                "NAVident" to navIdent.id,
                "oid" to "12345678-1234-1234-1234-123456789012",
            )
        ).serialize()}")
        header("Content-Type", "application/json")
        header("Accept", "application/json")
        setBody("""{ "kontorId": "$kontorId", "ident": "$fnr", "begrunnelse": null }""")
    }
}
