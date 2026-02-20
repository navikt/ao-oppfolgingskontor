package kafka.consumers

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kafka.producers.OppfolgingEndretTilordningMelding
import no.nav.db.Ident
import no.nav.domain.KontorEndringsType
import no.nav.domain.OppfolgingsperiodeId
import domain.IdenterOppslagFeil
import no.nav.kafka.processor.Retry
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
            brukAoRuting = true
        )
        val ident: Ident = randomFnr()
        val tilordningMelding = OppfolgingEndretTilordningMelding(
            kontorId = "3131",
            oppfolgingsperiodeId = UUID.randomUUID().toString(),
            ident = ident.value,
            kontorEndringsType = KontorEndringsType.AutomatiskNorgRuting
        )
        val record = Record(
            OppfolgingsperiodeId(UUID.fromString(tilordningMelding.oppfolgingsperiodeId)),
            tilordningMelding,
            ZonedDateTime.now().toEpochSecond()
        )

        processor.process(record).shouldBeInstanceOf<Retry<String, String>>()
    }

    @Test
    fun `Skal ikke publisere når brukAoRuting er false`() {
        var harPublisertMelding = false
        val processor = PubliserKontorTilordningProcessor(
            hentAlleIdenter = { IdenterOppslagFeil("PDL feiler") },
            publiserKontorTilordning = {
                harPublisertMelding = true
                Result.success(Unit)
            },
            brukAoRuting = false
        )
        val ident: Ident = randomFnr()
        val tilordningMelding = OppfolgingEndretTilordningMelding(
            kontorId = "3131",
            oppfolgingsperiodeId = UUID.randomUUID().toString(),
            ident = ident.value,
            kontorEndringsType = KontorEndringsType.AutomatiskNorgRuting
        )
        val record = Record(
            OppfolgingsperiodeId(UUID.fromString(tilordningMelding.oppfolgingsperiodeId)),
            tilordningMelding,
            ZonedDateTime.now().toEpochSecond()
        )

        processor.process(record)

        harPublisertMelding shouldBe false
    }

    @Test
    fun `Skal publisere når brukAoRuting er true`() {
        var harPublisertMelding = false
        val processor = PubliserKontorTilordningProcessor(
            hentAlleIdenter = { IdenterOppslagFeil("PDL feiler") },
            publiserKontorTilordning = {
                harPublisertMelding = true
                Result.success(Unit)
            },
            brukAoRuting = true
        )
        val ident: Ident = randomFnr()
        val tilordningMelding = OppfolgingEndretTilordningMelding(
            kontorId = "3131",
            oppfolgingsperiodeId = UUID.randomUUID().toString(),
            ident = ident.value,
            kontorEndringsType = KontorEndringsType.AutomatiskNorgRuting
        )
        val record = Record(
            OppfolgingsperiodeId(UUID.fromString(tilordningMelding.oppfolgingsperiodeId)),
            tilordningMelding,
            ZonedDateTime.now().toEpochSecond()
        )

        processor.process(record)

        harPublisertMelding shouldBe true
    }
}