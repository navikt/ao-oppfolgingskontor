package services

import db.table.IdentMappingTable
import db.table.IdentMappingTable.historisk
import db.table.IdentMappingTable.identType
import db.table.IdentMappingTable.internIdent
import db.table.IdentMappingTable.slettetHosOss
import db.table.IdentMappingTable.updatedAt
import db.table.InternIdentSequence
import db.table.nextValueOf
import io.kotest.assertions.throwables.shouldThrow
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
import no.nav.db.IdentSomKanLagres
import no.nav.db.Npid
import no.nav.http.client.IdentFunnet
import no.nav.http.client.IdenterFunnet
import no.nav.http.client.IdenterIkkeFunnet
import no.nav.http.client.IdenterOppslagFeil
import no.nav.http.client.IdenterResult
import no.nav.kafka.processor.Retry
import no.nav.kafka.processor.Skip
import no.nav.person.pdl.aktor.v2.Aktor
import no.nav.person.pdl.aktor.v2.Identifikator
import no.nav.person.pdl.aktor.v2.Type
import no.nav.utils.flywayMigrationInTest
import no.nav.utils.randomAktorId
import no.nav.utils.randomFnr
import org.apache.kafka.streams.processor.api.Record
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.batchUpsert
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.time.ZonedDateTime

class IdentServiceTest {

    @Test
    fun `skal cache påfølgende kall til hentFnrFraAktorId`() = runTest {
        flywayMigrationInTest()
        val aktorId = AktorId("4141112121313", AKTIV)
        val fnr = Fnr("01020304052", AKTIV)
        var invocations = 0
        val identProvider = { oppslagId: String ->
            invocations++
            IdenterFunnet(listOf(fnr, aktorId), Ident.validateOrThrow(oppslagId, UKJENT))
        }
        val identService = IdentService(identProvider)

        identService.veksleAktorIdIForetrukketIdent(aktorId)
        identService.veksleAktorIdIForetrukketIdent(aktorId)

        invocations shouldBe 1
    }

    @Test
    fun `skal gi ut npid hvis det bare finnes npid i folkeregisteridentene`() = runTest {
        flywayMigrationInTest()
        val npid = Npid("01020304050", AKTIV)
        val aktorId = AktorId("4141112122441", AKTIV)
        val alleIdenterProvider = { ident: String ->
            IdenterFunnet(listOf(npid, aktorId), inputIdent = Ident.validateOrThrow(ident, UKJENT))
        }
        val identService = IdentService(alleIdenterProvider)

        identService.veksleAktorIdIForetrukketIdent(aktorId)
        val npidUt = identService.veksleAktorIdIForetrukketIdent(aktorId)

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
                inputIdent = Ident.validateOrThrow(ident, UKJENT)
            )
        }
        val identService = IdentService(fnrProvider)

        identService.veksleAktorIdIForetrukketIdent(aktorId)
        val dnrUt = identService.veksleAktorIdIForetrukketIdent(aktorId)

        dnrUt.shouldBeInstanceOf<IdentFunnet>()
        dnrUt.ident shouldBe dnr
    }


//    @Test
    fun `skal gi fnr ved søk på npid`() = runTest {
        flywayMigrationInTest()
        val npid = Npid("01220304055", AKTIV)
        val fnr = Fnr("11111111111", AKTIV)
        val aktorId = AktorId("2938764298763", AKTIV)

        val fnrProvider = { ident: String ->
            IdenterFunnet(
                listOf(npid, aktorId, fnr),
                inputIdent = Ident.validateOrThrow(ident, UKJENT)
            )
        }

        val identService = IdentService(fnrProvider)
        val foretrukketIdent = identService.veksleAktorIdIForetrukketIdent(aktorId)

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
        identService.veksleAktorIdIForetrukketIdent(gammelAktorId)

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
    fun `håndterEndringPåIdenter - skal kaste exception ved uhåndtert feil i pdl-kall`() = runTest   {
        val identProvider: suspend (String) -> IdenterFunnet = { throw IllegalStateException("Noe gikk galt") }
        val identService = IdentService(identProvider)
        val aktorId = AktorId("2938764298763", AKTIV)

        shouldThrow<IllegalStateException> { identService.håndterEndringPåIdenter(aktorId) }
    }

    @Test
    fun `skal gi dnr for Tenor som starter med 4,5,6,7`() = testApplication {
        val identValue = "42876702740";

        val ident = Ident.validateOrThrow(identValue, UKJENT)

        ident.shouldBeInstanceOf<Dnr>()
        ident.value shouldBe identValue
    }

    @Test
    fun `skal gi dnr for Dolly som starter med 4,5,6,7`() = testApplication {
        val identValue = "42456702740";

        val ident = Ident.validateOrThrow(identValue, UKJENT)

        ident.shouldBeInstanceOf<Dnr>()
        ident.value shouldBe identValue
    }

    @Test
    fun `aktor-v2 endring - skal oppdatere identer når det kommer endring`() = runTest {
        flywayMigrationInTest()

        val identProvider: suspend (String) -> IdenterFunnet = { aktorId ->
            IdenterFunnet(
                emptyList(),
            Ident.validateOrThrow(aktorId, UKJENT)
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
        ), Ident.validateOrThrow(innkommendeAktorId, UKJENT)) }
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

        val identProvider: suspend (String) -> IdenterFunnet = { aktorId -> IdenterFunnet(emptyList(), Ident.validateOrThrow(aktorId, AKTIV)) }
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

        val identProvider: suspend (String) -> IdenterFunnet = { aktorId -> IdenterFunnet(emptyList(), Ident.validateOrThrow(aktorId, AKTIV)) }
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
    fun `skal finne internId på ny aktørId selv om gammel aktørId er opphørt hvis fnr finnes`() = runTest {
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
            ), Ident.validateOrThrow(nyMenIkkeLagretEndaAktorId, AKTIV))
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
    fun `veksleAktorIdIForetrukketIdent - skal fallback til å hente identer på nytt hvis bare aktorid finnes i id-mapping`() = runTest {
        flywayMigrationInTest()
        val aktorId = AktorId("2221219811121", AKTIV)
        val fnr = Fnr("12112198111", AKTIV)
        val identProvider: suspend (String) -> IdenterFunnet = { input -> IdenterFunnet(
            listOf(fnr, aktorId),
            Ident.validateOrThrow(input, UKJENT))
        }
        val identService = IdentService(identProvider)
        /* Only having AktorId will  */
        transaction {
            val internId = nextValueOf(InternIdentSequence)
            IdentMappingTable.insert {
                it[IdentMappingTable.id] = aktorId.value
                it[IdentMappingTable.historisk] = false
                it[IdentMappingTable.identType] = "AKTOR_ID"
                it[IdentMappingTable.internIdent] = internId
            }
        }

        identService.veksleAktorIdIForetrukketIdent(aktorId).shouldBeInstanceOf<IdentFunnet>()
    }

    @Test
    fun `skal håndtere merge`() = runTest {
        flywayMigrationInTest()
        val aktorId1 = randomAktorId()
        val fnr1 = randomFnr()
        val internIdent1 = 123456L
        val internIdent2 = internIdent1 + 1
        val aktorId2 = randomAktorId()
        val fnr2 = randomFnr()
        val irrelevantIdentProvider: suspend (String) -> IdenterIkkeFunnet = { input -> IdenterIkkeFunnet("Ikke brukt") }
        val identService = IdentService(irrelevantIdentProvider)
        lagreIdenter(identer = listOf(aktorId1, fnr1), nyInternIdent = internIdent1)
        lagreIdenter(identer = listOf(aktorId2, fnr2), nyInternIdent = internIdent2)

        identService.håndterEndringPåIdenter(
            aktorId = aktorId2,
            oppdaterteIdenterFraPdl = listOf(
                OppdatertIdent(fnr1, true),
                OppdatertIdent(aktorId1, true),
                OppdatertIdent(fnr2, false),
                OppdatertIdent(aktorId2, false)
            )
        )

        val identer = (identService.hentAlleIdenter(fnr2) as IdenterFunnet).identer
        identer.size shouldBe 4
        identer.map { it.internIdent }
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

    fun lagreIdenter(identer: List<Ident>, nyInternIdent: Long) {
        return transaction {
            IdentMappingTable.batchInsert(identer) { ident ->
                this[slettetHosOss] = null
                this[historisk] = ident.historisk == HISTORISK
                this[internIdent] = nyInternIdent
                this[identType] = ident.toIdentType()
                this[updatedAt] = ZonedDateTime.now().toOffsetDateTime()
            }.size
        }
    }
}
