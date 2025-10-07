package no.nav.utils

import db.table.IdentMappingTable
import db.table.InternIdentSequence
import db.table.nextValueOf
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import no.nav.db.FlywayPlugin
import no.nav.db.Fnr
import no.nav.db.Ident
import no.nav.db.Ident.HistoriskStatus.HISTORISK
import no.nav.db.flywayMigrate
import no.nav.db.table.OppfolgingsperiodeTable
import no.nav.domain.OppfolgingsperiodeId
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import services.IdentServiceTest.IdentFraDb
import services.toIdentType
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

fun flywayMigrationInTest() {
    flywayMigrate(TestDb.postgres)
}

fun gittBrukerUnderOppfolging(
    fnr: Fnr,
    oppfolgingsperiodeId: OppfolgingsperiodeId = OppfolgingsperiodeId(UUID.randomUUID()),
): OppfolgingsperiodeId {
    transaction {
        OppfolgingsperiodeTable.insert {
            it[this.id] = fnr.value
            it[this.oppfolgingsperiodeId] = oppfolgingsperiodeId.value
            it[this.startDato] = ZonedDateTime.now().toOffsetDateTime()
        }
    }
    return oppfolgingsperiodeId
}

fun lagreIdentIIdentmappingTabell(ident: Ident, internIdent: Long? = null) {
    transaction {
        IdentMappingTable.insert {
            it[IdentMappingTable.identType] = ident.toIdentType()
            it[IdentMappingTable.id] = ident.value
            it[IdentMappingTable.internIdent] = internIdent ?: nextValueOf(InternIdentSequence)
            it[IdentMappingTable.historisk] = ident.historisk == HISTORISK
        }
    }
}

fun hentInternId(ident: Ident): Long {
    return transaction {
        IdentMappingTable.select(IdentMappingTable.internIdent)
            .where { IdentMappingTable.id eq ident.value }
            .map { row -> row[IdentMappingTable.internIdent]}
            .first()
    }
}