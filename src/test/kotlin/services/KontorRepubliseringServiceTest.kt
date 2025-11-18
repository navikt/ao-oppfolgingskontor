package services

import io.kotest.assertions.withClue
import io.kotest.common.runBlocking
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import no.nav.db.entity.OppfolgingsperiodeEntity
import no.nav.db.table.OppfolgingsperiodeTable
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
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.experimental.suspendedTransactionAsync
import org.jetbrains.exposed.sql.transactions.transaction
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
        transaction {
            OppfolgingsperiodeTable.deleteAll()
        }
        val republiserteKontorer = mutableListOf<KontorSomSkalRepubliseres>()

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
        gittIdentIMapping(listOf(fnr, aktorId), null, 20312)
        gittKontorNavn(kontorNavn, kontorId)
        gittIdentMedKontor(
            ident = fnr,
            kontorId = kontorId,
            oppfolgingsperiodeId = periode,
        )

        var count = 0L
        newSuspendedTransaction {
            count = OppfolgingsperiodeEntity.count()
            kontorRepubliseringService.republiserKontorer()
        }

        withClue("Forventet ${count} republiserte kontoret men fikk ${republiserteKontorer.size}") {
            republiserteKontorer.size shouldBe count
        }
        val testKontor = republiserteKontorer
            .find { it.oppfolgingsperiodeId == periode && it.aktorId == aktorId }
        val updatedAt = testKontor!!.updatedAt // TODO: Les updatedAt fra kontorTilordningen

        testKontor shouldBe KontorSomSkalRepubliseres(
            ident =  fnr,
            aktorId = aktorId,
            kontorId = kontorId,
            kontorNavn = kontorNavn,
            updatedAt = updatedAt,
            oppfolgingsperiodeId = periode,
            kontorEndringsType = KontorEndringsType.AutomatiskNorgRuting
        )
    }

    @Test
    fun `Skal ikke republisere kontor for personer som ikke er under oppfølging`() = runTest {
        transaction {
            OppfolgingsperiodeTable.deleteAll()
        }
        val republiserteKontorer = mutableListOf<KontorSomSkalRepubliseres>()

        val kontorRepubliseringService = KontorRepubliseringService(
            { republiserteKontorer.add(it) },
            dataSource,
            {}
        )
        val fnr = randomFnr()
        val aktorId = randomAktorId()
        val kontorId = KontorId("2121")
        val kontorNavn = KontorNavn("Nav Helsfyr")
        gittIdentIMapping(listOf(fnr, aktorId), null, 20312)
        gittKontorNavn(kontorNavn, kontorId)
        gittIdentMedKontor(
            ident = fnr,
            kontorId = kontorId,
            oppfolgingsperiodeId = null,
        )

        var count = 0L
        newSuspendedTransaction {
            count = OppfolgingsperiodeEntity.count()
            kontorRepubliseringService.republiserKontorer()
        }

        withClue("Forventet ${count} republiserte kontoret men fikk ${republiserteKontorer.size}") {
            republiserteKontorer.size shouldBe count
        }
        val testKontor = republiserteKontorer
            .find { it.aktorId == aktorId }

        testKontor shouldBe null
    }

}