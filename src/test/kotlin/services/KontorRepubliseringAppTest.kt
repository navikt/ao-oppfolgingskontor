package services

import io.ktor.server.testing.testApplication
import kafka.producers.KontorEndringProducer
import no.nav.domain.KontorId
import no.nav.domain.KontorNavn
import no.nav.http.configureKontorRepubliseringModule
import no.nav.partitioner
import no.nav.utils.TestDb
import no.nav.utils.flywayMigrationInTest
import no.nav.utils.gittBrukerUnderOppfolging
import no.nav.utils.gittIdentIMapping
import no.nav.utils.gittIdentMedKontor
import no.nav.utils.gittKontorNavn
import no.nav.utils.randomAktorId
import no.nav.utils.randomFnr
import org.apache.kafka.clients.producer.MockProducer
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.Test

class KontorRepubliseringAppTest {

    @Test
    fun `skal kunne republisere kontor via http endpoint`() {
        flywayMigrationInTest()
        val fnr = randomFnr()
        val aktorId = randomAktorId()
        val kontorId = KontorId("3121")
        val kontorNavn = KontorNavn("Nav Hei")
        val mockProducer = MockProducer(true, partitioner, StringSerializer(), StringSerializer())
        val kontorEndringProducer = KontorEndringProducer(
            producer = mockProducer,
            kontorTopicNavn = "navn",
            kontorNavnProvider = { kontorNavn },
            aktorIdProvider = { aktorId },
        )
        val kontorRepubliseringService = KontorRepubliseringService(
            kontorEndringProducer::republiserKontor,
            TestDb.postgres,
            {}
        )
        val periode = gittBrukerUnderOppfolging(fnr)
        gittIdentIMapping(listOf(fnr, aktorId), null, 20312)
        gittKontorNavn(kontorNavn, kontorId)
        gittIdentMedKontor(
            ident = fnr,
            kontorId = kontorId,
            oppfolgingsperiodeId = periode,
        )

        testApplication {

            application {
                configureKontorRepubliseringModule(
                    kontorRepubliseringService,
                )
            }
        }
    }

}