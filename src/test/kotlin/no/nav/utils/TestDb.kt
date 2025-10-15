package no.nav.utils

import db.table.IdentMappingTable
import db.table.IdentMappingTable.historisk
import db.table.IdentMappingTable.identType
import db.table.IdentMappingTable.internIdent
import db.table.IdentMappingTable.slettetHosOss
import db.table.InternIdentSequence
import db.table.nextValueOf
import io.ktor.server.application.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import no.nav.db.FlywayPlugin
import no.nav.db.Fnr
import no.nav.db.Ident
import no.nav.db.Ident.HistoriskStatus.HISTORISK
import no.nav.db.IdentSomKanLagres
import no.nav.db.flywayMigrate
import no.nav.db.table.OppfolgingsperiodeTable
import no.nav.domain.KontorId
import no.nav.domain.KontorTilordning
import no.nav.domain.OppfolgingsperiodeId
import no.nav.domain.events.OppfolgingsPeriodeStartetLokalKontorTilordning
import no.nav.http.client.GeografiskTilknytningKommuneNr
import no.nav.services.KontorForGtNrFantDefaultKontor
import no.nav.services.KontorTilordningService
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import services.ingenSensitivitet
import services.toIdentType
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.util.*
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

fun gittIdentIMapping(ident: Ident, internIdent: Long) = gittIdentIMapping(identer = listOf(ident), internId = internIdent)

fun gittIdentIMapping(ident: Ident, slettet: OffsetDateTime? = null) = gittIdentIMapping(listOf(ident), slettet)

fun gittIdentIMapping(identer: List<Ident>, slettet: OffsetDateTime? = null, internId: Long? = null) {
    transaction {
        IdentMappingTable.batchInsert(identer) { ident ->
            this[internIdent] = internId ?: nextValueOf(InternIdentSequence)
            this[identType] = ident.toIdentType()
            this[historisk] = ident.historisk == HISTORISK
            this[IdentMappingTable.id] = ident.value
            this[slettetHosOss] = slettet
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

fun gittIdentMedKontor(ident: IdentSomKanLagres, kontorId: KontorId) {
    KontorTilordningService.tilordneKontor(
        OppfolgingsPeriodeStartetLokalKontorTilordning(
            KontorTilordning(
                ident,
                kontorId,
                OppfolgingsperiodeId(UUID.randomUUID())
            ),
            kontorForGt = KontorForGtNrFantDefaultKontor(
                kontorId,
                ingenSensitivitet.skjermet,
                ingenSensitivitet.strengtFortroligAdresse,
                geografiskTilknytningNr = GeografiskTilknytningKommuneNr("2121")
            )

        )
    )
}
