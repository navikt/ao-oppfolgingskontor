package services

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import no.nav.domain.KontorEndringsType
import no.nav.domain.KontorId
import no.nav.domain.KontorNavn
import no.nav.utils.TestDb
import no.nav.utils.flywayMigrationInTest
import no.nav.utils.gittBrukerUnderOppfolging
import no.nav.utils.gittIdentIMapping
import no.nav.utils.gittIdentMedKontor
import no.nav.utils.gittKontorNavn
import no.nav.utils.randomAktorId
import no.nav.utils.randomFnr
import no.nav.utils.*
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

        val updatedAt = republiserteKontorer.first().updatedAt // TODO: Les updatedAt fra kontorTilordningen
        republiserteKontorer shouldBe mutableListOf(
            KontorSomSkalRepubliseres(
                ident =  fnr,
                aktorId = aktorId,
                kontorId = kontorId,
                kontorNavn = kontorNavn,
                updatedAt = updatedAt,
                oppfolgingsperiodeId = periode,
                kontorEndringsType = KontorEndringsType.AutomatiskNorgRuting
            )
        )
    }
}