package kafka.producers

import domain.IdenterFunnet
import domain.IdenterIkkeFunnet
import domain.IdenterOppslagFeil
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import no.nav.db.AktorId
import no.nav.db.Ident.HistoriskStatus.AKTIV
import no.nav.db.InternIdent
import no.nav.domain.KontorNavn
import no.nav.utils.randomFnr
import no.nav.utils.randomInternIdent
import org.apache.kafka.clients.producer.MockProducer
import org.apache.kafka.common.serialization.LongSerializer
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.Test

class KontorEndringProducerTest {

    private fun mockProducer() = MockProducer<Long, String?>(true, LongSerializer(), StringSerializer())

    private fun kontorEndringProducer(producer: MockProducer<Long, String?> = mockProducer()): KontorEndringProducer {
        return KontorEndringProducer(
            producer = producer,
            kontorTopicNavn = "test-topic",
            kontorNavnProvider = { KontorNavn("Test KontorNavn") },
            hentAlleIdenter = { throw RuntimeException("Should not be called in this test") },
            brukAoRuting = true
        )
    }

    @Test
    fun `publiserTombstone - skal sende tombstone-melding med internIdent som key og null som value`() {
        val producer = mockProducer()
        val internIdent = InternIdent(12345L)

        kontorEndringProducer(producer).publiserTombstone(internIdent)

        producer.history().size shouldBe 1
        producer.history()[0].key() shouldBe 12345L
        producer.history()[0].value() shouldBe null
        producer.history()[0].topic() shouldBe "test-topic"
    }

    @Test
    fun `finnPubliseringsIdenter - skal returnere Triple med fnr, aktorId og internIdent`() = runTest {
        val fnr = randomFnr()
        val aktorId = AktorId("1234567890123", AKTIV)
        val internIdent = randomInternIdent()
        val identerFunnet = IdenterFunnet(listOf(fnr, aktorId), fnr, internIdent)
        val producer = KontorEndringProducer(
            producer = mockProducer(),
            kontorTopicNavn = "test-topic",
            kontorNavnProvider = { KontorNavn("Test KontorNavn") },
            hentAlleIdenter = { identerFunnet },
            brukAoRuting = true
        )

        val (fnrResult, aktorIdResult, internIdentResult) = producer.finnPubliseringsIdenter(fnr)

        fnrResult shouldBe fnr
        aktorIdResult shouldBe aktorId
        internIdentResult shouldBe internIdent
    }

    @Test
    fun `finnPubliseringsIdenter - skal kaste exception når identer ikke finnes`() = runTest {
        val fnr = randomFnr()
        val producer = KontorEndringProducer(
            producer = mockProducer(),
            kontorTopicNavn = "test-topic",
            kontorNavnProvider = { KontorNavn("Test KontorNavn") },
            hentAlleIdenter = { IdenterIkkeFunnet("Fant ikke identer") },
            brukAoRuting = true
        )

        shouldThrow<RuntimeException> {
            producer.finnPubliseringsIdenter(fnr)
        }
    }

    @Test
    fun `finnPubliseringsIdenter - skal kaste exception ved oppslagsfeil`() = runTest {
        val fnr = randomFnr()
        val producer = KontorEndringProducer(
            producer = mockProducer(),
            kontorTopicNavn = "test-topic",
            kontorNavnProvider = { KontorNavn("Test KontorNavn") },
            hentAlleIdenter = { IdenterOppslagFeil("Feil i PDL") },
            brukAoRuting = true
        )

        shouldThrow<RuntimeException> {
            producer.finnPubliseringsIdenter(fnr)
        }
    }
}
