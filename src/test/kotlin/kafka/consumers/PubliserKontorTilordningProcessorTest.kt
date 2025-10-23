package kafka.consumers

import io.kotest.matchers.types.shouldBeInstanceOf
import kafka.producers.OppfolgingEndretTilordningMelding
import no.nav.db.Ident
import no.nav.domain.KontorEndringsType
import no.nav.domain.OppfolgingsperiodeId
import no.nav.http.client.IdenterOppslagFeil
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

}