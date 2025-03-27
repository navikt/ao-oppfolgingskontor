package no.nav.db

import io.ktor.events.EventDefinition
import io.ktor.server.application.*
import org.flywaydb.core.Flyway

val FlywayMigrationStarting: EventDefinition<Application> = EventDefinition()
val FlywayMigrationFinished: EventDefinition<Application> = EventDefinition()

val FlywayPlugin: ApplicationPlugin<Unit> = createApplicationPlugin("Flyway") {
    val dataSource =  PostgresDataSource.getDataSource(applicationConfig)
    application.monitor.raise(FlywayMigrationStarting, application)
    Flyway.configure()
        .dataSource(dataSource)
        .load()
        .migrate()
    application.monitor.raise(FlywayMigrationFinished, application)
}