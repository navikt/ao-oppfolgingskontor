package services

import io.kotest.matchers.shouldBe
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.test.runTest
import no.nav.db.Fnr
import no.nav.db.Npid
import no.nav.http.client.FnrFunnet
import no.nav.utils.flywayMigrationInTest
import org.junit.Test

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
                    FnrFunnet(fnr)
                }
                val aktorId = "4141112121"
                val identService = IdentService(fnrProvider)

                identService.hentFnrFraAktorId(aktorId)
                identService.hentFnrFraAktorId(aktorId)

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
                    FnrFunnet(npid)
                }
                val aktorId = "4141112122"
                val identService = IdentService(fnrProvider)

                identService.hentFnrFraAktorId(aktorId)
                val npidUt = identService.hentFnrFraAktorId(aktorId)

                npidUt shouldBe npidUt
            }
        }
    }
}