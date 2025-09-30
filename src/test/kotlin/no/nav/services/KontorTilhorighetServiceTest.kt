package no.nav.services

import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import no.nav.db.Fnr
import no.nav.db.IdentSomKanLagres
import no.nav.db.entity.ArenaKontorEntity
import no.nav.db.table.ArenaKontorTable
import no.nav.db.table.KontorhistorikkTable
import no.nav.domain.KontorEndringsType
import no.nav.domain.KontorType
import no.nav.domain.System
import no.nav.http.client.IdenterFunnet
import no.nav.utils.flywayMigrationInTest
import no.nav.utils.randomFnr
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.statements.InsertStatement
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Test
import java.time.OffsetDateTime

class KontorTilhorighetServiceTest {

    @Test
    fun `getArenaKontorMedOppfolgingsperiode skal kunne hente arenakontor som mangler oppfolgingsperiode`() = runTest {
        flywayMigrationInTest()
        val ident = randomFnr()

        val entry = gittArenaHistorikkEntry(ident)
        val entryId = entry[KontorhistorikkTable.id]
        gittArenaKontor(ident, entryId)

        KontorTilhorighetService(
            mockk(),
            mockk(),
            { IdenterFunnet( listOf(ident), ident) }
        ).getArenaKontorMedOppfolgingsperiode(ident)
    }

    @Test
    fun `getArenaKontorMedOppfolgingsperiode skal kunne hente arenakontor som mangler historikk-entry`() = runTest {
        flywayMigrationInTest()
        val ident = randomFnr()

        transaction {
            ArenaKontorTable.insert {
                it[id] = ident.value
                it[kontorId] = "2121"
                it[sistEndretDatoArena] = OffsetDateTime.now()
            }
        }

        KontorTilhorighetService(
            mockk(),
            mockk(),
            { IdenterFunnet( listOf(ident), ident) }
        ).getArenaKontorMedOppfolgingsperiode(ident)
    }

    fun gittArenaHistorikkEntry(ident: Fnr): InsertStatement<Number> {
        return transaction {
            val registrant = System()
            KontorhistorikkTable.insert {
                it[KontorhistorikkTable.ident] = ident.value
                it[kontorId] = "2121"
                it[kontorType] = KontorType.ARENA.name
                it[kontorendringstype] = KontorEndringsType.TidligArenaKontorVedOppfolgingStart.name
                it[endretAv] = registrant.getIdent()
                it[endretAvType] = registrant.getType()
            }
        }
    }

    fun gittArenaKontor(ident: IdentSomKanLagres, entryId: EntityID<Int>? = null) {
        transaction {
            ArenaKontorTable.insert {
                it[id] = ident.value
                it[kontorId] = "2121"
                it[sistEndretDatoArena] = OffsetDateTime.now()
                it[historikkEntry] = entryId
            }
        }
    }
}