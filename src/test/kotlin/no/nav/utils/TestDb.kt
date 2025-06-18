package no.nav.utils

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import no.nav.db.FlywayPlugin
import org.jetbrains.exposed.sql.Database
import javax.sql.DataSource

object TestDb {
    val postgres: DataSource by lazy {
        EmbeddedPostgres.start().postgresDatabase
            .also {
                Database.connect(it)
            }
    }
}

fun Application.flywayMigrationInTest(): DataSource {
    install(FlywayPlugin) {
        this.dataSource = TestDb.postgres
    }
    return TestDb.postgres
}
