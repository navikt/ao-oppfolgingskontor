package services

import no.nav.utils.TestDb
import org.junit.jupiter.api.Test

class KontorRepubliseringServiceTest {
    private val dataSource = TestDb.postgres

    @Test
    fun `Skal kunne republisere kontor uten feil`() {
        var republiserteKontorer = mutableListOf<KontorSomSkalRepubliseres>()

        val kontorRepubliseringService = KontorRepubliseringService(
            { republiserteKontorer.add(it) },
            dataSource
        )
        kontorRepubliseringService.republiserKontorEndringer()
    }


}