package kafka.consumers

import io.kotest.matchers.equals.shouldBeEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldBeTypeOf
import io.ktor.server.testing.testApplication
import no.nav.db.Fnr
import no.nav.db.entity.ArbeidsOppfolgingKontorEntity
import no.nav.db.entity.GeografiskTilknyttetKontorEntity
import no.nav.db.table.ArbeidsOppfolgingKontorTable
import no.nav.db.table.GeografiskTilknytningKontorTable
import no.nav.domain.KontorId
import no.nav.http.client.poaoTilgang.GTKontorFeil
import no.nav.http.client.poaoTilgang.GTKontorFunnet
import no.nav.http.client.poaoTilgang.GTKontorResultat
import no.nav.kafka.consumers.AddressebeskyttelseEndret
import no.nav.kafka.consumers.BostedsadresseEndret
import no.nav.kafka.consumers.LeesahConsumer
import no.nav.kafka.processor.RecordProcessingResult
import no.nav.kafka.processor.Retry
import no.nav.person.pdl.leesah.adressebeskyttelse.Gradering
import no.nav.services.AutomatiskKontorRutingService
import no.nav.utils.flywayMigrationInTest
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Test
import java.time.ZonedDateTime

class LeesahConsumerTest {

    @Test
    fun `skal sjekke gt kontor på nytt ved bostedsadresse endret`() = testApplication {
        val fnr = "1234567890"
        val gammeltKontorId = "1234"
        val nyKontorId = "5678"
        application {
            flywayMigrationInTest()
            gittNåværendeGtKontor(fnr, KontorId(gammeltKontorId))
            val automatiskKontorRutingService = gittRutingServiceMedGtKontor(KontorId(nyKontorId))
            val leesahConsumer = LeesahConsumer(automatiskKontorRutingService)

            leesahConsumer.handterLeesahHendelse(BostedsadresseEndret(fnr))

            transaction {
                val kontorEtterEndirng = GeografiskTilknyttetKontorEntity[fnr]
                kontorEtterEndirng.kontorId shouldBe nyKontorId
            }
        }
    }

    @Test
    fun `skal sette både gt-kontor og ao-kontor ved addressebeskyttelse endret hvis det er nytt kontor`() = testApplication {
        val fnr = "1234567892"
        val gammeltKontorId = "1234"
        val nyKontorId = "5678"
        application {
            flywayMigrationInTest()
            gittNåværendeGtKontor(fnr, KontorId(gammeltKontorId))
            val automatiskKontorRutingService = gittRutingServiceMedGtKontor(KontorId(nyKontorId))
            val leesahConsumer = LeesahConsumer(automatiskKontorRutingService)

            leesahConsumer.handterLeesahHendelse(AddressebeskyttelseEndret(fnr, Gradering.STRENGT_FORTROLIG))

            transaction {
                val gtKontorEtterEndring = GeografiskTilknyttetKontorEntity[fnr]
                gtKontorEtterEndring.kontorId shouldBe nyKontorId

                val aoKontorEtterEndirng = ArbeidsOppfolgingKontorEntity[fnr]
                aoKontorEtterEndirng.kontorId shouldBe nyKontorId
            }
        }
    }

    @Test
    fun `skal ikke sette ao-kontor men gt-kontor ved addressebeskyttelse endret hvis det er nytt kontor`() = testApplication {
        val fnr = "1234567894"
        val gammelKontorId = "1234"
        val nyKontorId = "5678"
        application {
            flywayMigrationInTest()
            gittNåværendeAOKontor(fnr, KontorId(gammelKontorId))
            gittNåværendeGtKontor(fnr, KontorId(gammelKontorId))
            val automatiskKontorRutingService = gittRutingServiceMedGtKontor(KontorId(nyKontorId))
            val leesahConsumer = LeesahConsumer(automatiskKontorRutingService)

            leesahConsumer.handterLeesahHendelse(AddressebeskyttelseEndret(fnr, Gradering.UGRADERT))

            transaction {
                val gtKontorEtterEndring = GeografiskTilknyttetKontorEntity[fnr]
                gtKontorEtterEndring.kontorId shouldBe nyKontorId

                val aoKontorEtterEndirng = ArbeidsOppfolgingKontorEntity[fnr]
                aoKontorEtterEndirng.kontorId shouldBe gammelKontorId
            }
        }
    }

    @Test
    fun `skal håndtere at gt-provider returnerer GTKontorFeil`() = testApplication {
        val fnr = "4044567890"
        val automatiskKontorRutingService = defaultAutomatiskKontorRutingService(
            { GTKontorFeil("Noe gikk galt") }
        )
        val leesahConsumer = LeesahConsumer(automatiskKontorRutingService)

        val resultat = leesahConsumer.handterLeesahHendelse(BostedsadresseEndret(fnr))

        resultat.shouldBeTypeOf<Retry>()
        resultat.reason shouldBe "Kunne ikke håndtere endring i bostedsadresse pga feil ved henting av gt-kontor: Noe gikk galt"
    }

    @Test
    fun `skal håndtere at gt-provider kaster throwable`() = testApplication {
        val fnr = "4044567890"
        val automatiskKontorRutingService = defaultAutomatiskKontorRutingService(
            { throw Throwable("Noe gikk galt") }
        )
        val leesahConsumer = LeesahConsumer(automatiskKontorRutingService)

        val resultat = leesahConsumer.handterLeesahHendelse(BostedsadresseEndret(fnr))

        resultat.shouldBeTypeOf<Retry>()
        resultat.reason shouldBe "Uventet feil ved håndtering av endring i bostedsadresse"
    }

    private fun defaultAutomatiskKontorRutingService(
        gtProvider: suspend (fnr: String) -> GTKontorResultat
    ): AutomatiskKontorRutingService {
        return AutomatiskKontorRutingService(
            fnrProvider = { throw Throwable("Denne skal ikke brukes") },
            gtKontorProvider = gtProvider,
            aldersProvider = { throw Throwable("Denne skal ikke brukes") },
            profileringProvider = { throw Throwable("Denne skal ikke brukes") },
        )
    }

    private fun gittRutingServiceMedGtKontor(kontorId: KontorId): AutomatiskKontorRutingService {
        return defaultAutomatiskKontorRutingService(
            { GTKontorFunnet(kontorId) }
        )
    }

    private fun gittNåværendeGtKontor(fnr: Fnr, kontorId: KontorId) {
        transaction {
            GeografiskTilknytningKontorTable.insert {
                it[id] = fnr
                it[this.kontorId] = kontorId.id
                it[this.createdAt] = ZonedDateTime.now().toOffsetDateTime()
                it[this.updatedAt] = ZonedDateTime.now().toOffsetDateTime()
            }
        }
    }

    private fun gittNåværendeAOKontor(fnr: Fnr, kontorId: KontorId) {
        transaction {
            ArbeidsOppfolgingKontorTable.insert {
                it[id] = fnr
                it[this.kontorId] = kontorId.id
                it[endretAv] = "test"
                it[endretAvType] = "VEILEDER"
                it[createdAt] = ZonedDateTime.now().toOffsetDateTime()
                it[updatedAt] = ZonedDateTime.now().toOffsetDateTime()
            }
        }
    }
}