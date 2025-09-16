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
import no.nav.db.Ident.HistoriskStatus.AKTIV
import no.nav.db.Ident.HistoriskStatus.HISTORISK
import no.nav.db.Ident.HistoriskStatus.UKJENT
import no.nav.db.Npid
import no.nav.http.client.IdentFunnet
import no.nav.http.client.IdenterFunnet
import no.nav.http.graphql.generated.client.enums.IdentGruppe
import no.nav.http.graphql.generated.client.hentfnrquery.IdentInformasjon
import no.nav.kafka.processor.Retry
import no.nav.kafka.processor.Skip
import no.nav.person.pdl.aktor.v2.Aktor
import no.nav.person.pdl.aktor.v2.Identifikator
import no.nav.person.pdl.aktor.v2.Type
import no.nav.utils.flywayMigrationInTest
import org.apache.kafka.streams.processor.api.Record
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.batchUpsert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

class IdentServiceTest {

    @Test
    fun `skal cache påfølgende kall til hentFnrFraAktorId`() = runTest {
        flywayMigrationInTest()
        val aktorId = AktorId("4141112121313", UKJENT)
        val fnr = Fnr("01020304052", UKJENT)
        var invocations = 0
        val identProvider = { oppslagId: String ->
            invocations++
            IdenterFunnet(listOf(fnr, aktorId), Ident.of(oppslagId, UKJENT))
        }
        val identService = IdentService(identProvider)

        identService.hentForetrukketIdentFor(aktorId)
        identService.hentForetrukketIdentFor(aktorId)

        invocations shouldBe 1
    }

    @Test
    fun `skal gi ut npid hvis det bare finnes npid i folkeregisteridentene`() = runTest {
        flywayMigrationInTest()
        val npid = Npid("01020304050", AKTIV)
        val aktorId = AktorId("4141112122441", AKTIV)
        val alleIdenterProvider = { ident: String ->
            IdenterFunnet(listOf(npid, aktorId), inputIdent = Ident.of(ident, UKJENT))
        }
        val identService = IdentService(alleIdenterProvider)

        identService.hentForetrukketIdentFor(aktorId)
        val npidUt = identService.hentForetrukketIdentFor(aktorId)

        npidUt.shouldBeInstanceOf<IdentFunnet>()
        npidUt.ident shouldBe npid
    }

    @Test
    fun `skal gi ut dnr hvis det er beste match`() = runTest {
        flywayMigrationInTest()
        val npid = Npid("01220304052", AKTIV)
        val aktorId = AktorId("4141112122442", AKTIV)
        val dnr = Dnr("41020304052", AKTIV)

        val fnrProvider = { ident: String ->
            IdenterFunnet(
                listOf(npid, dnr, aktorId),
                inputIdent = Ident.of(ident, UKJENT)
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
        val npid = Npid("01220304055", AKTIV)
        val fnr = Fnr("11111111111", AKTIV)
        val aktorId = AktorId("2938764298763", AKTIV)

        val fnrProvider = { ident: String ->
            IdenterFunnet(
                listOf(npid, aktorId, fnr),
                inputIdent = Ident.of(ident, UKJENT)
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
        val npid = Npid("01220304055", AKTIV)
        val fnr = Fnr("11111111111", AKTIV)
        val gammelAktorId = AktorId("2938764298763", HISTORISK)
        val nyAktorId = AktorId("3938764298763", AKTIV)

        val identService = IdentService { _ ->
            IdenterFunnet(listOf(
                npid,
                gammelAktorId,
                fnr), npid)
        }
        identService.hentForetrukketIdentFor(npid)

        val oppdatertIdentService = IdentService { _ ->
            IdenterFunnet(listOf(
                npid,
                gammelAktorId,
                nyAktorId,
                fnr), npid)
        }
        val identer = oppdatertIdentService.håndterEndringPåIdenter(npid)

        identer shouldBe IdenterFunnet(listOf(
            npid,
            gammelAktorId,
            nyAktorId,
            fnr
        ), npid)
    }

    @Test
    fun `skal gi dnr for Tenor som starter med 4,5,6,7`() = testApplication {
        val identValue = "42876702740";

        val ident = Ident.of(identValue, UKJENT)

        ident.shouldBeInstanceOf<Dnr>()
        ident.value shouldBe identValue
    }

    @Test
    fun `skal gi dnr for Dolly som starter med 4,5,6,7`() = testApplication {
        val identValue = "42456702740";

        val ident = Ident.of(identValue, UKJENT)

        ident.shouldBeInstanceOf<Dnr>()
        ident.value shouldBe identValue
    }

    @Test
    fun `aktor-v2 endring - skal oppdatere identer når det kommer endring`() = runTest {
        flywayMigrationInTest()

        val identProvider: suspend (String) -> IdenterFunnet = { aktorId ->
            IdenterFunnet(
                emptyList(),
            Ident.of(aktorId, UKJENT)
            )
        }
        val identService = IdentService(identProvider)
        val aktorId = AktorId("2938764298763", AKTIV)
        val dnr = Dnr("48764298763", AKTIV)

        val internIdent = 4343L

        transaction {
            IdentMappingTable.batchUpsert(listOf(aktorId, dnr)) {
                this[IdentMappingTable.internIdent] = internIdent
                this[IdentMappingTable.historisk] = false
                this[IdentMappingTable.id] = it.value
                this[IdentMappingTable.identType] = it.toIdentType()
            }
        }

        val fnr = Fnr("38764298763", AKTIV)
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

        val aktorId = AktorId("2938764298763", AKTIV)
        val fnr = Fnr("18111298763", AKTIV)

        val identProvider: suspend (String) -> IdenterFunnet = { innkommendeAktorId -> IdenterFunnet(listOf(
            aktorId,
            fnr,
        ), Ident.of(innkommendeAktorId, UKJENT)) }
        val identService = IdentService(identProvider)

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

        val identProvider: suspend (String) -> IdenterFunnet = { aktorId -> IdenterFunnet(emptyList(), Ident.of(aktorId, AKTIV)) }
        val identService = IdentService(identProvider)
        val proccessor = IdentChangeProcessor(identService)
        val aktorId = AktorId("2938764298763", AKTIV)
        val fnr = Fnr("01010198765", AKTIV)
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

        val identProvider: suspend (String) -> IdenterFunnet = { aktorId -> IdenterFunnet(emptyList(), Ident.of(aktorId, AKTIV)) }
        val identService = IdentService(identProvider)
        val aktorId = AktorId("2938764298793", AKTIV)
        val dnr = Dnr("48764298868", AKTIV)
        val fnr = Fnr("01010196769", AKTIV)
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

    @Test
    fun `skal finne internId på ny aktørId selvom gammel aktørId er opphørt hvis fnr finnes`() = runTest {
        flywayMigrationInTest()

        val opphortAktorId = AktorId("4938764598763", HISTORISK)
        val nyAktorId = AktorId("2938764297763", AKTIV)
        val fnr = Fnr("02010198765", AKTIV)
        val internId = 31231L

        /* AktorId er slettet, men det finnes fortsatt FNR som ikke er slettet. Slettet ident dukker ikke opp i liste av identer på topicet */
        transaction {
            IdentMappingTable.batchInsert(listOf(opphortAktorId, fnr)) {
                this[IdentMappingTable.internIdent] = internId
                this[IdentMappingTable.historisk] = false
                this[IdentMappingTable.id] = it.value
                this[IdentMappingTable.identType] = it.toIdentType()
                this[IdentMappingTable.slettetHosOss] = if (it is AktorId) OffsetDateTime.now() else null
            }
        }

        val identProvider: suspend (String) -> IdenterFunnet = { nyMenIkkeLagretEndaAktorId ->
            IdenterFunnet(listOf(
                fnr, nyAktorId
            ), Ident.of(nyMenIkkeLagretEndaAktorId, AKTIV))
        }
        val identService = IdentService(identProvider)
        val proccessor = IdentChangeProcessor(identService)

        val payload = mockk<Aktor> {
            every { identifikatorer } returns listOf(
                Identifikator(fnr.value, Type.FOLKEREGISTERIDENT, true),
                Identifikator(nyAktorId.value, Type.AKTORID, true),
            )
        }
        proccessor.process(Record(nyAktorId.value, payload, 1010L))

        hentIdenter(internId) shouldBe listOf(
            IdentFraDb(opphortAktorId.value, "AKTOR_ID", false, true),
            IdentFraDb(fnr.value, "FNR", false, false),
            IdentFraDb(nyAktorId.value, "AKTOR_ID", false, false),
        )
    }

    @Test
    fun `IdentChangePrcessor - skal fange tekniske feil fra identService`() {
        flywayMigrationInTest()

        val identProvider: suspend (String) -> IdenterFunnet = { throw IllegalStateException("Noe gikk galt") }
        val identService = IdentService(identProvider)
        val proccessor = IdentChangeProcessor(identService)

        val aktorId = AktorId("2593876429711", AKTIV)
        val fnr = Fnr("02010198111", AKTIV)
        val payload = mockk<Aktor> {
            every { identifikatorer } returns listOf(
                Identifikator(fnr.value, Type.FOLKEREGISTERIDENT, true),
                Identifikator(aktorId.value, Type.AKTORID, true),
            )
        }

        val result = proccessor.process(Record(aktorId.value, payload, 1212L))

        result.shouldBeInstanceOf<Retry<String, Aktor>>()
    }

    @Test
    fun `IdentChangePrcessor - skal hopper over personer is test`() {
        flywayMigrationInTest()

        val identProvider: suspend (String) -> IdenterFunnet = { throw IllegalStateException("Fant ikke person: not_found") }
        val identService = IdentService(identProvider)
        val proccessor = IdentChangeProcessor(identService, skipPersonIkkeFunnet = true)

        val aktorId = AktorId("2593876429722", AKTIV)
        val fnr = Fnr("02010198122", AKTIV)
        val payload = mockk<Aktor> {
            every { identifikatorer } returns listOf(
                Identifikator(fnr.value, Type.FOLKEREGISTERIDENT, true),
                Identifikator(aktorId.value, Type.AKTORID, true),
            )
        }

        val result = proccessor.process(Record(aktorId.value, payload, 1212L))

        result.shouldBeInstanceOf<Skip<String, Aktor>>()
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
