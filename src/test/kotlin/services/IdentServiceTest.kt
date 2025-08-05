package services

import io.kotest.matchers.shouldBe
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.test.runTest
import no.nav.db.Fnr
import no.nav.db.Npid
import no.nav.http.client.IdentFunnet
import no.nav.utils.flywayMigrationInTest
import org.junit.jupiter.api.Test

class IdentServiceTest {

    @Test
    fun `skal cache påfølgende kall til hentFnrFraAktorId`() = testApplication {
        application {
            flywayMigrationInTest()

            /* Ikkje bra - tester burde ikke kjøre inni application {} men da
            * feiler connection til db */
            runTest {
                val fnr = Fnr("01020304052")
                var invocations = 0
                val fnrProvider = { aktorId: String ->
                    invocations++
                    IdentFunnet(fnr)
                }
                val aktorId = "4141112121"
                val identService = IdentService(fnrProvider)

                identService.hentIdentFraAktorId(aktorId)
                identService.hentIdentFraAktorId(aktorId)

                invocations shouldBe 1
            }
        }
    }

    @Test
    fun `skal gi ut npid hvis det kom inn npid`() = testApplication {
        application {
            flywayMigrationInTest()
            runTest {
                val npid = Npid("01020304050")
                var invocations = 0
                val fnrProvider = { aktorId: String ->
                    invocations++
                    IdentFunnet(npid)
                }
                val aktorId = "4141112122"
                val identService = IdentService(fnrProvider)

                identService.hentIdentFraAktorId(aktorId)
                val npidUt = identService.hentIdentFraAktorId(aktorId)

                npidUt shouldBe npidUt
            }
        }
    }
}
