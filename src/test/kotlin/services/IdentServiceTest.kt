package services

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
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
                val aktorId = AktorId("4141112121313")
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

                identService.hentForetrukketIdentFor(aktorId)
                identService.hentForetrukketIdentFor(aktorId)

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
                val aktorId = AktorId("4141112122441")
                val npIdIdentInformasjon = IdentInformasjon(
                    historisk = false,
                    gruppe = IdentGruppe.NPID,
                    ident = npid.value
                )
                val aktorIdIdentInformasjon = IdentInformasjon(
                    historisk = false,
                    gruppe = IdentGruppe.AKTORID,
                    ident = aktorId.value
                )
                val fnrProvider = { ident: String ->
                    IdenterFunnet(listOf(npIdIdentInformasjon, aktorIdIdentInformasjon), inputIdent = aktorId.value)
                }
                val identService = IdentService(fnrProvider)

                identService.hentForetrukketIdentFor(aktorId)
                val npidUt = identService.hentForetrukketIdentFor(aktorId)

                npidUt.shouldBeInstanceOf<IdentFunnet>()
                npidUt.ident shouldBe npid
            }
        }
    }

    @Test
    fun `skal gi fnr ved søk på npid`() = testApplication {
        application {
            flywayMigrationInTest()
            runTest {
                val npid = Npid("01020304055")
                val fnr = Fnr("11111111111")
                val aktorId = AktorId("2938764298763")
                val npIdIdentInformasjon = IdentInformasjon(
                    historisk = false,
                    gruppe = IdentGruppe.NPID,
                    ident = npid.value
                )
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

                val fnrProvider = { ident: String ->
                    IdenterFunnet(listOf(npIdIdentInformasjon, aktorIdIdentInformasjon, fnrIdentInformasjon), inputIdent = npid.value)
                }
                val identService = IdentService(fnrProvider)
                val foretrukketIdent = identService.hentForetrukketIdentFor(npid)

                foretrukketIdent.shouldBeInstanceOf<IdentFunnet>()
                foretrukketIdent.ident.shouldBeInstanceOf<Fnr>()
                foretrukketIdent.ident shouldBe fnr
            }
        }
    }
}
