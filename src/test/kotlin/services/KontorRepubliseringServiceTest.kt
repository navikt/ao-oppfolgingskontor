package services

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import no.nav.db.AktorId
import no.nav.db.Fnr
import no.nav.db.Ident
import no.nav.db.table.KontorNavnTable.kontorNavn
import no.nav.domain.KontorEndringsType
import no.nav.domain.KontorId
import no.nav.domain.KontorNavn
import no.nav.domain.OppfolgingsperiodeId
import no.nav.utils.TestDb
import no.nav.utils.flywayMigrationInTest
import no.nav.utils.gittBrukerUnderOppfolging
import no.nav.utils.gittIdentIMapping
import no.nav.utils.gittIdentMedKontor
import no.nav.utils.gittKontorNavn
import no.nav.utils.randomAktorId
import no.nav.utils.randomFnr
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class KontorRepubliseringServiceTest {
    private val dataSource = TestDb.postgres

    companion object {
        @BeforeAll
        @JvmStatic
        fun setup() {
            flywayMigrationInTest()
        }
    }


    @Test
    fun `Skal kunne republisere kontor uten feil`() = runTest {
        var republiserteKontorer = mutableListOf<KontorSomSkalRepubliseres>()

        val kontorRepubliseringService = KontorRepubliseringService(
            { republiserteKontorer.add(it) },
            dataSource,
            {}
        )
        val fnr = randomFnr()
        val aktorId = randomAktorId()
        val kontorId = KontorId("2121")
        val kontorNavn = KontorNavn("Nav Helsfyr")
        val periode = gittBrukerUnderOppfolging(fnr)
        gittIdentIMapping(listOf(fnr, aktorId), null, 1)
        gittKontorNavn(kontorNavn, kontorId)
        gittIdentMedKontor(
            ident = fnr,
            kontorId = kontorId,
            oppfolgingsperiodeId = periode,
        )

        kontorRepubliseringService.republiserKontorer()

        val updatedAt = republiserteKontorer.first().updatedAt
        republiserteKontorer shouldBe mutableListOf<KontorSomSkalRepubliseres>(
            KontorSomSkalRepubliseres(
                ident = Fnr("18117623396", Ident.HistoriskStatus.UKJENT),
                aktorId = AktorId("7319053121892", Ident.HistoriskStatus.UKJENT),
                kontorId = KontorId("2121"),
                kontorNavn = KontorNavn("Nav Helsfyr"),
                updatedAt = updatedAt,
                oppfolgingsperiodeId = periode,
                kontorEndringsType = KontorEndringsType.AutomatiskNorgRuting
            )
        )
    }


}