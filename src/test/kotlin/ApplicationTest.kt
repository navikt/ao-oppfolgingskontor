package no.nav

import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals

class ApplicationTest {


    @Test
    fun testRoot() = testApplication {
        val postgres = EmbeddedPostgres.start()

        application {
            module()
        }

        client.get("/").apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }


}
