package services

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import no.nav.db.InvalidIdent
import no.nav.db.ValidIdent
import no.nav.domain.KontorId
import no.nav.utils.flywayMigrationInTest
import no.nav.utils.gittIdentIMapping
import no.nav.utils.gittIdentMedKontor
import no.nav.utils.randomDnr
import no.nav.utils.randomFnr
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

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
        val ugyldigIdent = "321312"
        gittIdentIMapping(bruker1)
        gittIdentMedKontor(bruker1, kontorId1)
        gittIdentIMapping(bruker2)
        gittIdentMedKontor(bruker2, kontorId2)

        val result = KontorTilhorighetBulkService.getKontorTilhorighetBulk(
            listOf(
                ValidIdent(bruker1),
                ValidIdent(bruker2),
                InvalidIdent(ugyldigIdent),
                ValidIdent(brukerUtenKontor)
            ),
        )

        result.first() shouldBe KontorBulkDto(bruker1.value, kontorId1.id)
        result[1] shouldBe KontorBulkDto(bruker2.value, kontorId2.id)
        result[2] shouldBe KontorBulkDto(ugyldigIdent, null)
        result.last() shouldBe KontorBulkDto(brukerUtenKontor.value, null)
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
            listOf(ValidIdent(fnr)),
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
            listOf(ValidIdent(dnr)),
        )

        result shouldHaveSize 1
        result.first() shouldBe KontorBulkDto(dnr.value, null)
    }
}