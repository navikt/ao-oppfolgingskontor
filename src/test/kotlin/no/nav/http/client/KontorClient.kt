package no.nav.http.client

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import no.nav.NavAnsatt
import no.nav.db.Fnr
import no.nav.security.mock.oauth2.MockOAuth2Server

suspend fun HttpClient.settKontor(server: MockOAuth2Server, kontorId: String, fnr: Fnr, navAnsatt: NavAnsatt): HttpResponse {
    return post("/api/kontor") {
        header("Authorization", "Bearer ${server.issueToken(
            claims = mapOf("NAVident" to "${navAnsatt.navIdent}", "oid" to "${navAnsatt.navAnsattAzureId}"),
        ).serialize()}")
        header("Content-Type", "application/json")
        header("Accept", "application/json")
        setBody("""{ "kontorId": "$kontorId", "fnr": "$fnr", "begrunnelse": null }""")
    }
}
