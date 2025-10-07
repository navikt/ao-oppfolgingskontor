package services

import db.table.IdentMappingTable
import db.table.IdentMappingTable.historisk
import db.table.IdentMappingTable.identType
import db.table.IdentMappingTable.internIdent
import db.table.IdentMappingTable.slettetHosOss
import db.table.InternIdentSequence
import db.table.nextValueOf
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import no.nav.db.Ident
import no.nav.domain.KontorId
import no.nav.domain.KontorTilordning
import no.nav.domain.OppfolgingsperiodeId
import no.nav.domain.events.OppfolgingsPeriodeStartetLokalKontorTilordning
import no.nav.services.KontorTilordningService
import no.nav.utils.flywayMigrationInTest
import no.nav.utils.randomDnr
import no.nav.utils.randomFnr
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID

class KontorTilhorighetBulkServiceTest {

    companion object {
        @BeforeAll
        @JvmStatic
        fun setup() {
            flywayMigrationInTest()
        }
    }

    @Test
    fun `skal hente ut kontor på flere brukere samtidig`() {
        val bruker1 = randomFnr()
        val kontorId1 = KontorId("2121")
        val bruker2 = randomFnr()
        val kontorId2 = KontorId("3121")
        val brukerUtenKontor = randomFnr()


        gittIdentIMapping(bruker1)
        gittIdentMedKontor(bruker1, kontorId1)
        gittIdentIMapping(bruker2)
        gittIdentMedKontor(bruker2, kontorId2)

        val result = KontorTilhorighetBulkService.getKontorTilhorighetBulk(
            listOf(bruker1, bruker2, brukerUtenKontor),
        )

        result.first() shouldBe KontorBulkDto(bruker1.value, kontorId1.id)
        result[1] shouldBe KontorBulkDto(bruker2.value, kontorId2.id)
        result.last() shouldBe null
    }

    @Test
    fun `skal bruke foretrukket ident hvis det finnes flere kontor på samme brukere (lagret på flere identer)`() {
        val fnr = randomFnr()
        val kontorId1 = KontorId("2121")
        val dnr = randomDnr()
        val kontorId2 = KontorId("3121")

        gittIdentIMapping(listOf(fnr, dnr))
        gittIdentMedKontor(fnr, kontorId1)
        gittIdentMedKontor(dnr, kontorId2)

        val result = KontorTilhorighetBulkService.getKontorTilhorighetBulk(
            listOf(fnr),
        )

        result shouldHaveSize 1
        result.first() shouldBe KontorBulkDto(fnr.value, kontorId1.id)
    }

    @Test
    fun `skal ikke bruke slettede identer`() {
        val dnr = randomDnr()
        val kontorId2 = KontorId("3121")

        gittIdentIMapping(dnr, slettet = OffsetDateTime.now())
        gittIdentMedKontor(dnr, kontorId2)

        val result = KontorTilhorighetBulkService.getKontorTilhorighetBulk(
            listOf(dnr),
        )

        result shouldHaveSize 1
        result.first() shouldBe null
    }

    fun gittIdentIMapping(ident: Ident, slettet: OffsetDateTime? = null) = gittIdentIMapping(listOf(ident), slettet)
    fun gittIdentIMapping(identer: List<Ident>, slettet: OffsetDateTime? = null) {
        transaction {
            val nextInternId = nextValueOf(InternIdentSequence)
            IdentMappingTable.batchInsert(identer) { ident ->
                this[internIdent] = nextInternId
                this[identType] = ident.toIdentType()
                this[historisk] = false
                this[IdentMappingTable.id] = ident.value
                this[slettetHosOss] = slettet
            }
        }
    }

    fun gittIdentMedKontor(ident: Ident, kontorId: KontorId) {
        KontorTilordningService.tilordneKontor(
            OppfolgingsPeriodeStartetLokalKontorTilordning(
                KontorTilordning(
                    ident,
                    kontorId,
                    OppfolgingsperiodeId(UUID.randomUUID())
                ),
                ingenSensitivitet
            )
        )
    }

}