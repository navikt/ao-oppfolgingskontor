package no.nav.utils

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import no.nav.db.FlywayPlugin
import no.nav.db.Fnr
import no.nav.db.table.OppfolgingsperiodeTable
import no.nav.domain.OppfolgingsperiodeId
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.ZonedDateTime
import java.util.UUID
import javax.sql.DataSource

object TestDb {
    val postgres = EmbeddedPostgres.start().postgresDatabase
        .also { Database.connect(it) }
}

fun Application.flywayMigrationInTest(): DataSource {
    install(FlywayPlugin) {
        this.dataSource = TestDb.postgres
    }
    return TestDb.postgres
}

fun gittBrukerUnderOppfolging(
    fnr: Fnr,
): OppfolgingsperiodeId {
    val oppfolgingsperiodeId = OppfolgingsperiodeId(UUID.randomUUID())
    transaction {
        OppfolgingsperiodeTable.insert {
            it[this.id] = fnr.value
            it[this.oppfolgingsperiodeId] = oppfolgingsperiodeId.value
            it[this.startDato] = ZonedDateTime.now().toOffsetDateTime()
        }
    }
    return oppfolgingsperiodeId
}