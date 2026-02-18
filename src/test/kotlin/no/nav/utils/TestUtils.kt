package no.nav.utils

import eventsLogger.BigQueryClient
import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import net.javacrumbs.shedlock.provider.exposed.ExposedLockProvider
import no.nav.services.KontorTilordningService
import org.jetbrains.exposed.sql.Database

fun ApplicationTestBuilder.getJsonHttpClient(): HttpClient {
    return createClient {
        install(ContentNegotiation) { json() }
        install(Logging)
    }
}

val kontorTilordningService = KontorTilordningService(
    BigQueryClient(
        "ProjectId",
        ExposedLockProvider(Database.connect(TestDb.postgres))
    )
)