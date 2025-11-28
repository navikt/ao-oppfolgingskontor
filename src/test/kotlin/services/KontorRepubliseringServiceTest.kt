package services

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import no.nav.db.Ident
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
import no.nav.utils.randomDnr
import no.nav.utils.randomFnr
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
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
    fun `Skal kunne republisere kontor for alle brukere uten feil`() = runTest {
        val republiserteKontorer = mutableListOf<KontortilordningSomSkalRepubliseres>()

        val kontorRepubliseringService = KontorRepubliseringService(
            {
                republiserteKontorer.add(it)
                Result.success(Unit)
            },
            dataSource,
            {}
        )
        val fnr = randomFnr()
        val aktorId = randomAktorId()
        val kontorId = KontorId("2124")
        val kontorNavn = KontorNavn("Nav Etterstad")
        val periode = gittBrukerUnderOppfolging(fnr)
        gittIdentIMapping(listOf(fnr, aktorId), null, 20313)
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

        withClue("Forventet ${count} republiserte tilordninger men fikk ${republiserteKontorer.size}") {
            republiserteKontorer.size shouldBe count
        }
        val testKontor = republiserteKontorer
            .find { it.oppfolgingsperiodeId == periode && it.aktorId == aktorId }
        val updatedAt = testKontor!!.updatedAt // TODO: Les updatedAt fra kontorTilordningen

        testKontor shouldBe KontortilordningSomSkalRepubliseres(
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
    fun `Skal kunne republisere kontor for valgte brukere med kontor både på historisk og nåværende ident`() = runTest {
        transaction {
            OppfolgingsperiodeTable.deleteAll()
        }
        val republiserteKontorer = mutableListOf<KontortilordningSomSkalRepubliseres>()

        val kontorRepubliseringService = KontorRepubliseringService(
            {
                republiserteKontorer.add(it)
                Result.success(Unit)
            },
            dataSource,
            {}
        )
        val fnr = randomFnr()
        val dnr = randomDnr(Ident.HistoriskStatus.HISTORISK)
        val aktorId = randomAktorId()
        val kontorId = KontorId("1123")
        val gammeltKontor = KontorId("1127")
        val kontorNavn = KontorNavn("Nav Vålrenga")
        val periode = gittBrukerUnderOppfolging(dnr)
        gittIdentIMapping(listOf(fnr, aktorId, dnr), null, 25312)
        gittKontorNavn(kontorNavn, kontorId)
        gittIdentMedKontor(
            ident = dnr,
            kontorId = gammeltKontor,
            oppfolgingsperiodeId = periode,
        )
        gittIdentMedKontor(
            ident = fnr,
            kontorId = kontorId,
            oppfolgingsperiodeId = periode,
        )

        var count = 0L
        newSuspendedTransaction {
            count = OppfolgingsperiodeEntity.count()
            kontorRepubliseringService.republiserKontorer(listOf(periode))
        }

        withClue("Forventet ${count} republiserte tilordninger men fikk ${republiserteKontorer.size}") {
            republiserteKontorer.size shouldBe count
        }
        val testKontor = republiserteKontorer
            .find { it.oppfolgingsperiodeId == periode && it.aktorId == aktorId }
        val updatedAt = testKontor!!.updatedAt // TODO: Les updatedAt fra kontorTilordningen

        testKontor shouldBe KontortilordningSomSkalRepubliseres(
            ident =  fnr, // Blir mapped om til gyldig ident før publisering på topic
            aktorId = aktorId,
            kontorId = kontorId,
            kontorNavn = kontorNavn,
            updatedAt = updatedAt,
            oppfolgingsperiodeId = periode,
            kontorEndringsType = KontorEndringsType.AutomatiskNorgRuting
        )
    }

    @Test
    fun `Skal kunne republisere kontor for valgt bruker med bare kontor på gammel ident`() = runTest {
        transaction {
            OppfolgingsperiodeTable.deleteAll()
        }
        val republiserteKontorer = mutableListOf<KontortilordningSomSkalRepubliseres>()

        val kontorRepubliseringService = KontorRepubliseringService(
            {
                republiserteKontorer.add(it)
                Result.success(Unit)
            },
            dataSource,
            {}
        )
        val fnr = randomFnr()
        val dnr = randomDnr(Ident.HistoriskStatus.HISTORISK)
        val aktorId = randomAktorId()
        val kontorId = KontorId("2123")
        val kontorNavn = KontorNavn("Nav Helsfyr")
        val periode = gittBrukerUnderOppfolging(dnr)
        gittIdentIMapping(listOf(fnr, aktorId, dnr), null, 20312)
        gittKontorNavn(kontorNavn, kontorId)
        gittIdentMedKontor(
            ident = dnr,
            kontorId = kontorId,
            oppfolgingsperiodeId = periode,
        )

        var count = 0L
        newSuspendedTransaction {
            count = OppfolgingsperiodeEntity.count()
            kontorRepubliseringService.republiserKontorer(listOf(periode))
        }

        withClue("Forventet ${count} republiserte tilordninger men fikk ${republiserteKontorer.size}") {
            republiserteKontorer.size shouldBe count
        }
        val testKontor = republiserteKontorer
            .find { it.oppfolgingsperiodeId == periode && it.aktorId == aktorId }
        val updatedAt = testKontor!!.updatedAt // TODO: Les updatedAt fra kontorTilordningen

        testKontor shouldBe KontortilordningSomSkalRepubliseres(
            ident =  dnr, // Blir mapped om til gyldig ident før publisering på topic
            aktorId = aktorId,
            kontorId = kontorId,
            kontorNavn = kontorNavn,
            updatedAt = updatedAt,
            oppfolgingsperiodeId = periode,
            kontorEndringsType = KontorEndringsType.AutomatiskNorgRuting
        )
    }

    @Test
    fun `Skal kunne republisere kontor for valgt bruker med perioder på gammel ident og kontor ny ident`() = runTest {
        transaction {
            OppfolgingsperiodeTable.deleteAll()
        }
        val republiserteKontorer = mutableListOf<KontortilordningSomSkalRepubliseres>()

        val kontorRepubliseringService = KontorRepubliseringService(
            {
                republiserteKontorer.add(it)
                Result.success(Unit)
            },
            dataSource,
            {}
        )
        val fnr = randomFnr()
        val dnr = randomDnr(Ident.HistoriskStatus.HISTORISK)
        val aktorId = randomAktorId()
        val kontorId = KontorId("1133")
        val kontorNavn = KontorNavn("Nav Fyrstikk")
        val periode = gittBrukerUnderOppfolging(dnr)
        gittIdentIMapping(listOf(fnr, aktorId, dnr), null, 701112)
        gittKontorNavn(kontorNavn, kontorId)
        gittIdentMedKontor(
            ident = fnr,
            kontorId = kontorId,
            oppfolgingsperiodeId = periode,
        )

        var count = 0L
        newSuspendedTransaction {
            count = OppfolgingsperiodeEntity.count()
            kontorRepubliseringService.republiserKontorer(listOf(periode))
        }

        withClue("Forventet ${count} republiserte tilordninger men fikk ${republiserteKontorer.size}") {
            republiserteKontorer.size shouldBe count
        }
        val testKontor = republiserteKontorer
            .find { it.oppfolgingsperiodeId == periode && it.aktorId == aktorId }
        val updatedAt = testKontor!!.updatedAt // TODO: Les updatedAt fra kontorTilordningen

        testKontor shouldBe KontortilordningSomSkalRepubliseres(
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
        val republiserteKontorer = mutableListOf<KontortilordningSomSkalRepubliseres>()

        val kontorRepubliseringService = KontorRepubliseringService(
            {
                republiserteKontorer.add(it)
                Result.success(Unit)
            },
            dataSource,
            {}
        )
        val fnr = randomFnr()
        val aktorId = randomAktorId()
        val kontorId = KontorId("2121")
        val kontorNavn = KontorNavn("Nav Helsfyr")
        gittIdentIMapping(listOf(fnr, aktorId), null, 20311)
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