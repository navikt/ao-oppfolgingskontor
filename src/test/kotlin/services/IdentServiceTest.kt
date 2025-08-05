package services

import io.kotest.matchers.shouldBe
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.test.runTest
import no.nav.db.AktorId
import no.nav.db.Fnr
import no.nav.db.Npid
import no.nav.http.client.IdentFunnet
import no.nav.http.client.IdenterFunnet
import no.nav.http.graphql.generated.client.enums.IdentGruppe
import no.nav.http.graphql.generated.client.hentfnrquery.IdentInformasjon
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
                val aktorId = AktorId("41411121213131")
                val fnr = Fnr("01020304052")
                val fnrIdentInformasjon = IdentInformasjon(
                    historisk = false,
                    gruppe = IdentGruppe.FOLKEREGISTERIDENT,
                    ident = fnr.value
                )
                val aktorIdIdentInformasjon = IdentInformasjon(
                    historisk = false,
                    gruppe = IdentGruppe.AKTORID,
                    ident = aktorId.value
                )
                var invocations = 0
                val identProvider = { aktorId: String ->
                    invocations++
                    IdenterFunnet(listOf(fnrIdentInformasjon, aktorIdIdentInformasjon), aktorId)
                }
                val identService = IdentService(identProvider)

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
                val identInformasjon = IdentInformasjon(
                    historisk = false,
                    gruppe = IdentGruppe.FOLKEREGISTERIDENT,
                    ident = npid.value
                )
                val fnrProvider = { aktorId: String -> IdenterFunnet(listOf(identInformasjon), inputIdent = aktorId) }
                val aktorId = AktorId("41411121224411")
                val identService = IdentService(fnrProvider)

                identService.hentIdentFraAktorId(aktorId)
                val npidUt = identService.hentIdentFraAktorId(aktorId)

                npidUt shouldBe npidUt
            }
        }
    }
}
