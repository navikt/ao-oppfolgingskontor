package kafka.consumers

import io.kotest.common.runBlocking
import io.kotest.matchers.equals.shouldBeEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kafka.producers.KontorTilordningMelding
import no.nav.db.Ident
import no.nav.http.client.IdentFunnet
import no.nav.http.client.IdenterFunnet
import no.nav.http.client.IdenterOppslagFeil
import no.nav.kafka.processor.Commit
import no.nav.kafka.processor.Retry
import no.nav.utils.randomAktorId
import no.nav.utils.randomFnr
import org.apache.kafka.streams.processor.api.Record
import org.junit.jupiter.api.Test
import java.time.ZonedDateTime
import java.util.UUID

class PubliserKontorTilordningProcessorTest {

    @Test
    fun `Skal gi retry når publiserKontorTilordning feiler`() {
        val processor = PubliserKontorTilordningProcessor(
            hentAlleIdenter = { IdenterOppslagFeil("PDL feiler") },
            publiserKontorTilordning = { Result.failure(Exception("Feilet")) },
            publiserTombstone = { Result.success(Unit) }
        )
        val ident: Ident = randomFnr()
        val record = Record(
            ident,
            KontorTilordningMelding(
                kontorId = "3131",
                oppfolgingsPeriodeId = UUID.randomUUID().toString(),
                ident = ident.value
            ),
            ZonedDateTime.now().toEpochSecond()
        )

        processor.process(record).shouldBeInstanceOf<Retry<String, String>>()
    }

    @Test
    fun `Skal kalle publiserTombstone med alle identer`() {
        val ident: Ident = randomFnr()
        val aktorId: Ident = randomAktorId()
        val funnedeIdenter = IdenterFunnet(listOf(ident, aktorId), ident)
        val tombstonedeIdenter = mutableListOf<IdenterFunnet>()
        val publiserTombstoneMockk: suspend (IdenterFunnet) -> Result<Unit> = { ident ->
            tombstonedeIdenter.add(ident)
            Result.success(Unit)
        }

        val processor = PubliserKontorTilordningProcessor(
            hentAlleIdenter = { funnedeIdenter },
            publiserKontorTilordning = { Result.failure(Exception("Feilet")) },
            publiserTombstone = publiserTombstoneMockk
        )
        val record: Record<Ident, KontorTilordningMelding> = Record(
            ident,
            null,
            ZonedDateTime.now().toEpochSecond()
        )

        processor.process(record).shouldBeInstanceOf<Commit<String, String?>>()

        tombstonedeIdenter shouldBe listOf(funnedeIdenter)
    }

    @Test
    fun `Skal gi retry når publiserTombstone feiler`() {
        val processor = PubliserKontorTilordningProcessor(
            hentAlleIdenter = { IdenterOppslagFeil("PDL feiler") },
            publiserKontorTilordning = { Result.success(Unit) },
            publiserTombstone = { Result.failure(Exception("Feilet")) }
        )
        val ident: Ident = randomFnr()
        val record: Record<Ident, KontorTilordningMelding> = Record(
            ident,
            null,
            ZonedDateTime.now().toEpochSecond()
        )

        val result = processor.process(record)
        result.shouldBeInstanceOf<Retry<String, String>>()
        result.reason.shouldBe("Kunne ikke publisere tombstone på ao-kontor til kafka: PDL feiler")
    }

}