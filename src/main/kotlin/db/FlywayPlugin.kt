package no.nav.db

import io.ktor.events.EventDefinition
import io.ktor.server.application.*
import org.flywaydb.core.Flyway
import javax.sql.DataSource

val FlywayMigrationStarting: EventDefinition<Application> = EventDefinition()
val FlywayMigrationFinished: EventDefinition<Application> = EventDefinition()

class FlywayPluginConfig(
    var dataSource: DataSource? = null
)

val FlywayPlugin: ApplicationPlugin<FlywayPluginConfig> = createApplicationPlugin("Flyway", ::FlywayPluginConfig) {
    val dataSource = requireNotNull(pluginConfig.dataSource) { "DataSource is required for Flyway" }
    application.monitor.raise(FlywayMigrationStarting, application)
    Flyway.configure()
        .dataSource(dataSource)
        .load()
        .migrate()
    application.monitor.raise(FlywayMigrationFinished, application)
}