package no.nav.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.config.ApplicationConfig
import org.jetbrains.exposed.sql.Database
import javax.sql.DataSource

object PostgresDataSource {
    private var dataSource: HikariDataSource? = null
    fun getDataSource(config: ApplicationConfig): HikariDataSource {
        if (dataSource == null) {
            dataSource = configureDb(config)
            return dataSource!!
        } else {
            return dataSource!!
        }
    }
}

private fun configureDb(config: ApplicationConfig): HikariDataSource {
    val host = config.property("postgres.host").getString()
    val port = config.property("postgres.port").getString()
    val databaseName = config.property("postgres.database-name").getString()
    val user = config.property("postgres.user").getString()
    val pw = config.property("postgres.password").getString()
    val sslRootCert = config.property("postgres.ssl-root-cert").getString()
    val sslMode = config.property("postgres.ssl-mode").getString()
    val sslCert = config.property("postgres.ssl-cert").getString()

    return HikariDataSource(HikariConfig().apply {
        driverClassName = "org.postgresql.Driver"
        jdbcUrl = config.property("postgres.jdbc-url").getString()
        maximumPoolSize = 20
        isAutoCommit = true
        initializationFailTimeout = 5000
        minimumIdle = 1
        username = user
        password = pw
        validate()
    })
}

fun Application.configureDatabase() : Database {
    val dataSource = PostgresDataSource.getDataSource(environment.config)
    val database = Database.connect(dataSource)
    install(FlywayPlugin) {
        this.dataSource = dataSource
    }
    return database
}