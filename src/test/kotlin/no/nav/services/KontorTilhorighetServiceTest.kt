package no.nav.services

import domain.IdenterFunnet
import domain.Systemnavn
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import no.nav.db.Fnr
import no.nav.db.IdentSomKanLagres
import no.nav.db.table.ArenaKontorTable
import no.nav.db.table.KontorhistorikkTable
import no.nav.domain.KontorEndringsType
import no.nav.domain.KontorType
import no.nav.domain.System
import no.nav.utils.flywayMigrationInTest
import no.nav.utils.randomFnr
import no.nav.utils.randomInternIdent
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.UUID
import no.nav.db.Ident
import no.nav.db.table.ArbeidsOppfolgingKontorTable
import no.nav.db.table.GeografiskTilknytningKontorTable
import no.nav.db.table.OppfolgingsperiodeTable
import no.nav.domain.KontorNavn
import no.nav.domain.OppfolgingsperiodeId
import no.nav.utils.randomDnr
import org.jetbrains.exposed.v1.core.statements.InsertStatement
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.upsert
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class KontorTilhorighetServiceTest {
    private val kontorNavnService = mockk<KontorNavnService>()

    @BeforeEach
    fun setup() {
        coEvery { kontorNavnService.getKontorNavn(any()) } returns KontorNavn("Testkontor")
    }

    @Test
    fun `getKontorTilhorighet skal hente ao-kontor for bruker`() = runTest {
        flywayMigrationInTest()
        val ident = randomFnr()
        val identer = IdenterFunnet(listOf(ident), ident, randomInternIdent())

        val gtHistorikkEntry = gittGTKontorHistorikkEntry(ident)
        val gtEntryId = gtHistorikkEntry[KontorhistorikkTable.id]
        gittGTKontor(ident, gtEntryId)

        val aoHistorikkEntry = gittAOKontorHistorikkEntry(ident)
        val aoEntryId = aoHistorikkEntry[KontorhistorikkTable.id]
        gittAOKontor(ident, aoEntryId, OppfolgingsperiodeId(UUID.randomUUID()))

        val kontor = KontorTilhorighetService(
            kontorNavnService = kontorNavnService,
            hentAlleIdenter = { identer }
        ).getKontorTilhorighet(identer)

        kontor?.kontorId shouldBe "2020"
        kontor?.kontorType shouldBe KontorType.ARBEIDSOPPFOLGING
    }

    @Test
    fun `getKontorTilhorighet skal hente ao-kontor som er lagret på gammel ident`() = runTest {
        flywayMigrationInTest()
        val gammelIdent = randomDnr(identStatus = Ident.HistoriskStatus.HISTORISK)
        val ident = randomFnr()
        val identer = IdenterFunnet(listOf(ident, gammelIdent), ident, randomInternIdent())

        val gtHistorikkEntry = gittGTKontorHistorikkEntry(ident)
        val gtEntryId = gtHistorikkEntry[KontorhistorikkTable.id]
        gittGTKontor(ident, gtEntryId)

        val aoHistorikkEntry = gittAOKontorHistorikkEntry(gammelIdent)
        val aoEntryId = aoHistorikkEntry[KontorhistorikkTable.id]
        gittAOKontor(gammelIdent, aoEntryId, OppfolgingsperiodeId(UUID.randomUUID()))

        val kontor = KontorTilhorighetService(
            kontorNavnService = kontorNavnService,
            hentAlleIdenter = { identer }
        ).getKontorTilhorighet(identer)

        kontor?.kontorType shouldBe KontorType.ARBEIDSOPPFOLGING
        kontor?.kontorId shouldBe "2020"
    }

    @Test
    fun `getArenaKontorMedOppfolgingsperiode skal kunne hente arenakontor som mangler oppfolgingsperiode`() = runTest {
        flywayMigrationInTest()
        val ident = randomFnr()

        val entry = gittArenaHistorikkEntry(ident)
        val entryId = entry[KontorhistorikkTable.id]
        gittArenaKontor(ident, entryId)

        KontorTilhorighetService(
            mockk(),
            { IdenterFunnet(listOf(ident), ident, randomInternIdent()) }
        ).getArenaKontorMedOppfolgingsperiode(ident)
    }

    fun gittGTKontorHistorikkEntry(ident: Fnr): InsertStatement<Number> {
        return transaction {
            val registrant = System(Systemnavn.VEILARBOPPFOLGING)
            KontorhistorikkTable.insert {
                it[KontorhistorikkTable.ident] = ident.value
                it[kontorId] = "2121"
                it[kontorType] = KontorType.GEOGRAFISK_TILKNYTNING.name
                it[kontorendringstype] = KontorEndringsType.GTKontorVedOppfolgingStart.name
                it[endretAv] = registrant.getIdent()
                it[endretAvType] = registrant.getType()
            }
        }
    }

    fun gittGTKontor(ident: IdentSomKanLagres, entryId: EntityID<Int>) {
        transaction {
            GeografiskTilknytningKontorTable.upsert {
                it[id] = ident.value
                it[kontorId] = "2121"
                it[gt] = "1234"
                it[gtType] = "Kommune"
                it[updatedAt] = ZonedDateTime.now().toOffsetDateTime()
                it[historikkEntry] = entryId
            }
        }
    }

    fun gittAOKontorHistorikkEntry(ident: IdentSomKanLagres): InsertStatement<Number> {
        return transaction {
            val registrant = System(Systemnavn.VEILARBOPPFOLGING)
            KontorhistorikkTable.insert {
                it[KontorhistorikkTable.ident] = ident.value
                it[kontorId] = "2020"
                it[kontorType] = KontorType.ARBEIDSOPPFOLGING.name
                it[kontorendringstype] = KontorEndringsType.AutomatiskNorgRuting.name
                it[endretAv] = registrant.getIdent()
                it[endretAvType] = registrant.getType()
            }
        }
    }

    fun gittAOKontor(ident: IdentSomKanLagres, entryId: EntityID<Int>, oppfolgingsperiode: OppfolgingsperiodeId) {
        val registrant = System(Systemnavn.VEILARBOPPFOLGING)
        transaction {
            OppfolgingsperiodeTable.upsert {
                it[id] = ident.value
                it[this.startDato] = ZonedDateTime.now().toOffsetDateTime()
                it[this.oppfolgingsperiodeId] = oppfolgingsperiode.value
                it[this.updatedAt] = OffsetDateTime.now(ZoneOffset.systemDefault())
            }
            ArbeidsOppfolgingKontorTable.upsert {
                it[kontorId] = "2020"
                it[id] = ident.value
                it[endretAv] = registrant.getIdent()
                it[endretAvType] = registrant.getType()
                it[updatedAt] = ZonedDateTime.now().toOffsetDateTime()
                it[historikkEntry] = entryId
                it[oppfolgingsperiodeId] = oppfolgingsperiode.value
            }
        }
    }

    fun gittArenaHistorikkEntry(ident: Fnr): InsertStatement<Number> {
        return transaction {
            val registrant = System(Systemnavn.ARENA)
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

    fun gittArenaKontor(ident: IdentSomKanLagres, entryId: EntityID<Int>) {
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