package no.nav

import io.ktor.server.application.*
import no.nav.db.FlywayPlugin
import no.nav.db.PostgresDataSource
import no.nav.http.configureArbeidsoppfolgingskontorModule
import no.nav.http.graphql.configureGraphQlModule
import no.nav.kafka.KafkaStreamsPlugin
import org.jetbrains.exposed.sql.Database

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.module() {
    configureMonitoring()
    configureHTTP()
    configureSecurity()
    configureRouting()
    val dataSource = PostgresDataSource.getDataSource(environment.config)
    Database.connect(dataSource)
    install(FlywayPlugin) {
        this.dataSource = dataSource
    }
    install(KafkaStreamsPlugin)
    configureGraphQlModule()
    configureArbeidsoppfolgingskontorModule()
}
