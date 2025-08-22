package services

import db.table.IdentMappingTable
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.server.testing.*
import io.mockk.every
import io.mockk.mockk
import kafka.consumers.IdentChangeProcessor
import kafka.consumers.OppdatertIdent
import kotlinx.coroutines.test.runTest
import no.nav.db.AktorId
import no.nav.db.Dnr
import no.nav.db.Fnr
import no.nav.db.Ident
import no.nav.db.Npid
import no.nav.http.client.IdentFunnet
import no.nav.http.client.IdenterFunnet
import no.nav.http.graphql.generated.client.enums.IdentGruppe
import no.nav.http.graphql.generated.client.hentfnrquery.IdentInformasjon
import no.nav.person.pdl.aktor.v2.Aktor
import no.nav.person.pdl.aktor.v2.Identifikator
import no.nav.utils.flywayMigrationInTest
import org.apache.kafka.streams.processor.api.Record
import org.jetbrains.exposed.sql.batchUpsert
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test

class IdentServiceTest {

    @Test
    fun `skal cache påfølgende kall til hentFnrFraAktorId`() = runTest {
        flywayMigrationInTest()
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

    @Test
    fun `skal gi ut npid hvis det bare finnes npid`() = runTest {
        flywayMigrationInTest()
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

    @Test
    fun `skal gi ut dnr hvis det er beste match`() = runTest {
        flywayMigrationInTest()
        val npid = Npid("01220304052")
        val aktorId = AktorId("4141112122442")
        val dnr = Dnr("41020304052")
        val npIdIdentInformasjon = IdentInformasjon(
            historisk = false,
            gruppe = IdentGruppe.NPID,
            ident = npid.value
        )
        val dnrIdentInformasjon = IdentInformasjon(
            historisk = false,
            gruppe = IdentGruppe.FOLKEREGISTERIDENT,
            ident = dnr.value
        )
        val aktorIdIdentInformasjon = IdentInformasjon(
            historisk = false,
            gruppe = IdentGruppe.AKTORID,
            ident = aktorId.value
        )
        val fnrProvider = { ident: String ->
            IdenterFunnet(
                listOf(npIdIdentInformasjon, dnrIdentInformasjon, aktorIdIdentInformasjon),
                inputIdent = aktorId.value
            )
        }
        val identService = IdentService(fnrProvider)

        identService.hentForetrukketIdentFor(aktorId)
        val dnrUt = identService.hentForetrukketIdentFor(aktorId)

        dnrUt.shouldBeInstanceOf<IdentFunnet>()
        dnrUt.ident shouldBe dnr
    }

    @Test
    fun `skal gi fnr ved søk på npid`() = runTest {
        flywayMigrationInTest()
        val npid = Npid("01220304055")
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
            IdenterFunnet(
                listOf(npIdIdentInformasjon, aktorIdIdentInformasjon, fnrIdentInformasjon),
                inputIdent = npid.value
            )
        }

        val identService = IdentService(fnrProvider)
        val foretrukketIdent = identService.hentForetrukketIdentFor(npid)

        foretrukketIdent.shouldBeInstanceOf<IdentFunnet>()
        foretrukketIdent.ident.shouldBeInstanceOf<Fnr>()
        foretrukketIdent.ident shouldBe fnr
    }

    @Test
    fun `skal oppdatere identer ved merge på aktorid`() = runTest {
        flywayMigrationInTest()
        val npid = Npid("01220304055")
        val fnr = Fnr("11111111111")
        val gammelAktorId = AktorId("2938764298763")
        val nyAktorId = AktorId("3938764298763")
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
        val gammelAktorIdIdentInformasjonIkkeHistorisk = IdentInformasjon(
            historisk = false,
            gruppe = IdentGruppe.AKTORID,
            ident = gammelAktorId.value
        )
        val gammelAktorIdIdentInformasjon = IdentInformasjon(
            historisk = true,
            gruppe = IdentGruppe.AKTORID,
            ident = gammelAktorId.value
        )
        val nyAktorIdIdentInformasjon = IdentInformasjon(
            historisk = false,
            gruppe = IdentGruppe.AKTORID,
            ident = nyAktorId.value
        )

        val identService = IdentService { _ ->
            IdenterFunnet(listOf(
                npIdIdentInformasjon,
                gammelAktorIdIdentInformasjonIkkeHistorisk,
                fnrIdentInformasjon), npid.value)
        }
        identService.hentForetrukketIdentFor(npid)

        val oppdatertIdentService = IdentService { _ ->
            IdenterFunnet(listOf(
                npIdIdentInformasjon,
                gammelAktorIdIdentInformasjon,
                nyAktorIdIdentInformasjon,
                fnrIdentInformasjon), npid.value)
        }
        val identer = oppdatertIdentService.håndterEndringPåIdenter(npid)

        identer shouldBe IdenterFunnet(listOf(
            npIdIdentInformasjon,
            gammelAktorIdIdentInformasjon,
            nyAktorIdIdentInformasjon,
            fnrIdentInformasjon
        ), npid.value)
    }

    @Test
    fun `skal gi dnr for Tenor som starter med 4,5,6,7`() = testApplication {
        val identValue = "42876702740";

        val ident = Ident.of(identValue)

        ident.shouldBeInstanceOf<Dnr>()
        ident.value shouldBe identValue
    }

    @Test
    fun `skal gi dnr for Dolly som starter med 4,5,6,7`() = testApplication {
        val identValue = "42456702740";

        val ident = Ident.of(identValue)

        ident.shouldBeInstanceOf<Dnr>()
        ident.value shouldBe identValue
    }

    @Test
    fun `aktor-v2 endring - skal oppdatere identer når det kommer endring`() = runTest {
        flywayMigrationInTest()

        val identProvider: suspend (String) -> IdenterFunnet = { aktorId -> IdenterFunnet(emptyList(), aktorId) }
        val identService = IdentService(identProvider)
        val aktorId = AktorId("2938764298763")
        val dnr = Dnr("48764298763")
        val fnr = Fnr("38764298763")
        val internIdent = 4343L

        transaction {
            IdentMappingTable.batchUpsert(listOf(aktorId, dnr)) {
                this[IdentMappingTable.internIdent] = internIdent
                this[IdentMappingTable.historisk] = false
                this[IdentMappingTable.id] = it.value
                this[IdentMappingTable.identType] = it.toIdentType()
            }
        }

        val innkommendeIdenter = listOf(
            OppdatertIdent(aktorId, false),
            OppdatertIdent(dnr, true),
            OppdatertIdent(fnr, false)
        )

        identService.håndterEndringPåIdenter(aktorId, innkommendeIdenter)

        hentIdenter(internIdent) shouldBe listOf(
            IdentFraDb(aktorId.value, "AKTOR_ID", false, false),
            IdentFraDb(dnr.value, "DNR", true, false),
            IdentFraDb(fnr.value, "FNR", false, false),
        )
    }

    @Test
    fun `aktor-v2 endring - skal ikke oppdatere identer når vi ikke har noe intern-ident på bruker`() = runTest {
        flywayMigrationInTest()

        val identProvider: suspend (String) -> IdenterFunnet = { aktorId -> IdenterFunnet(emptyList(), aktorId) }
        val identService = IdentService(identProvider)
        val aktorId = AktorId("2938764298763")
        val fnr = Fnr("38764298763")

        val innkommendeIdenter = listOf(
            OppdatertIdent(aktorId, false),
            OppdatertIdent(fnr, false)
        )

        val resultRows = identService.håndterEndringPåIdenter(aktorId, innkommendeIdenter)

        resultRows shouldBe 0
    }

    @Test
    fun `aktor-v2 endring - skal kun sette aktørId i key som slettet når det kommer en tombstone`() = runTest {
        flywayMigrationInTest()

        val identProvider: suspend (String) -> IdenterFunnet = { aktorId -> IdenterFunnet(emptyList(), aktorId) }
        val identService = IdentService(identProvider)
        val proccessor = IdentChangeProcessor(identService)
        val aktorId = AktorId("2938764298763")
        val fnr = Fnr("01010198765")
        val internId = 1231231231L

        transaction {
            IdentMappingTable.batchUpsert(listOf(aktorId, fnr)) {
                this[IdentMappingTable.internIdent] = internId
                this[IdentMappingTable.historisk] = false
                this[IdentMappingTable.id] = it.value
                this[IdentMappingTable.identType] = it.toIdentType()
            }
        }

        proccessor.process(Record(aktorId.value, null, 1010L))

        hentIdenter(internId) shouldBe listOf(
            IdentFraDb(aktorId.value, "AKTOR_ID", false, true),
            IdentFraDb(fnr.value, "FNR", false, false),
        )
    }
    @Test
    fun `aktor-v2 endring - skal slette identer som ikke er med i endringssettet`() = runTest {
        flywayMigrationInTest()

        val identProvider: suspend (String) -> IdenterFunnet = { aktorId -> IdenterFunnet(emptyList(), aktorId) }
        val identService = IdentService(identProvider)
        val aktorId = AktorId("2938764298793")
        val dnr = Dnr("48764298868")
        val fnr = Fnr("01010196769")
        val internId = 1231231291L

        transaction {
            IdentMappingTable.batchUpsert(listOf(aktorId, dnr, fnr)) {
                this[IdentMappingTable.internIdent] = internId
                this[IdentMappingTable.historisk] = false
                this[IdentMappingTable.id] = it.value
                this[IdentMappingTable.identType] = it.toIdentType()
            }
        }

        val innkommendeIdenter = listOf(
            OppdatertIdent(aktorId, false),
            OppdatertIdent(fnr, false)
        )

        identService.håndterEndringPåIdenter(aktorId, innkommendeIdenter)

        hentIdenter(internId) shouldBe listOf(
            IdentFraDb(aktorId.value, "AKTOR_ID", false, false),
            IdentFraDb(dnr.value, "DNR", false, true),
            IdentFraDb(fnr.value, "FNR", false, false),
        )
    }

    data class IdentFraDb(
        val ident: String,
        val type: String,
        val historisk: Boolean,
        val slettet: Boolean,
    )

    fun hentIdenter(internId: Long): List<IdentFraDb> {
        return transaction {
            IdentMappingTable.select(IdentMappingTable.id, IdentMappingTable.slettetHosOss, IdentMappingTable.historisk, IdentMappingTable.identType)
                .where { IdentMappingTable.internIdent eq internId }
                .map { row -> IdentFraDb(
                    row[IdentMappingTable.id].value,
                    row[IdentMappingTable.identType],
                    row[IdentMappingTable.historisk],
                    row[IdentMappingTable.slettetHosOss] != null
                )  }
        }
    }

    fun mockAktor(identer: List<Identifikator> = emptyList()): Aktor {
        return mockk<Aktor> {
            every { identifikatorer } returns identer
        }
    }
}
