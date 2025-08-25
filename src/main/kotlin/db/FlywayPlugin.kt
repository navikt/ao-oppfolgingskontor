package no.nav.db

import io.ktor.events.EventDefinition
import io.ktor.server.application.*
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import javax.sql.DataSource

val FlywayMigrationStarting: EventDefinition<Application> = EventDefinition()
val FlywayMigrationFinished: EventDefinition<Application> = EventDefinition()

class FlywayPluginConfig(
    var dataSource: DataSource? = null
)

val FlywayPlugin: ApplicationPlugin<FlywayPluginConfig> = createApplicationPlugin("Flyway", ::FlywayPluginConfig) {
    val dataSource = requireNotNull(pluginConfig.dataSource) { "DataSource is required for Flyway" }
    application.monitor.raise(FlywayMigrationStarting, application)
    flywayMigrate(dataSource)
    application.monitor.raise(FlywayMigrationFinished, application)
}

fun flywayMigrate(dataSource: DataSource) {
    val logger = LoggerFactory.getLogger("FlywayPlugin::class.java")
    logger.info("Starting Flyway migration")
    Flyway.configure()
        .validateMigrationNaming(true)
        .dataSource(dataSource)
        .load()
        .migrate()
    logger.info("Flyway migration finished")
}