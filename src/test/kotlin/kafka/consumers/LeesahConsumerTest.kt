package kafka.consumers

import io.kotest.matchers.shouldBe
import io.ktor.server.testing.testApplication
import no.nav.db.Fnr
import no.nav.db.entity.ArbeidsOppfolgingKontorEntity
import no.nav.db.entity.GeografiskTilknyttetKontorEntity
import no.nav.db.table.ArbeidsOppfolgingKontorTable
import no.nav.db.table.GeografiskTilknytningKontorTable
import no.nav.domain.KontorId
import no.nav.http.client.AlderFunnet
import no.nav.http.client.FnrFunnet
import no.nav.http.client.arbeidssogerregisteret.ProfileringsResultat
import no.nav.http.client.poaoTilgang.GTKontorFunnet
import no.nav.kafka.consumers.AddressebeskyttelseEndret
import no.nav.kafka.consumers.BostedsadresseEndret
import no.nav.kafka.consumers.LeesahConsumer
import no.nav.person.pdl.leesah.adressebeskyttelse.Gradering
import no.nav.services.AutomatiskKontorRutingService
import no.nav.services.ProfileringFunnet
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
            val automatiskKontorRutingService = gittKontorINorg(fnr, KontorId(nyKontorId))
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
            val automatiskKontorRutingService = gittKontorINorg(fnr, KontorId(nyKontorId))
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
            val automatiskKontorRutingService = gittKontorINorg(fnr, KontorId(nyKontorId))
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

    private fun gittKontorINorg(fnr: Fnr, kontorId: KontorId): AutomatiskKontorRutingService {
        return AutomatiskKontorRutingService(
            fnrProvider = { FnrFunnet(fnr) },
            gtKontorProvider = { GTKontorFunnet(kontorId) },
            aldersProvider = { AlderFunnet(20) },
            profileringProvider = { ProfileringFunnet(ProfileringsResultat.ANTATT_BEHOV_FOR_VEILEDNING) },
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