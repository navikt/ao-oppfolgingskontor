package kafka.consumers

import domain.IdenterOppslagFeil
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.ZonedDateTime
import java.util.UUID
import kafka.producers.OppfolgingEndretTilordningMelding
import no.nav.db.Ident
import no.nav.domain.KontorEndringsType
import no.nav.domain.OppfolgingsperiodeId
import no.nav.kafka.processor.Retry
import no.nav.utils.randomFnr
import org.apache.kafka.streams.processor.api.Record
import org.junit.jupiter.api.Test

class PubliserKontorTilordningProcessorTest {

    /* Innholdet i meldingene er ikke viktig i disse testene */
    val ident: Ident = randomFnr()
    val tilordningMelding = OppfolgingEndretTilordningMelding(
        kontorId = "3131",
        oppfolgingsperiodeId = UUID.randomUUID().toString(),
        ident = ident.value,
        kontorEndringsType = KontorEndringsType.AutomatiskNorgRuting
    )
    val topic = "test-topic"


    @Test
    fun `skal toStringe OppfolgingsPeriode`() {
        OppfolgingsperiodeId(UUID.randomUUID()).toString() shouldBe "OppfolgingsperiodeId(value=dd52f208-9d0e-4f0b-b0f3-4e421aedec1a)"
    }


    @Test
    fun `Skal gi retry når publiserKontorTilordning feiler`() {
        val processor = PubliserKontorTilordningProcessor(
            hentAlleIdenter = { IdenterOppslagFeil("PDL feiler") },
            publiserKontorTilordning = { Result.failure(Exception("Feilet")) },
        )

        val record = Record(
            OppfolgingsperiodeId(UUID.fromString(tilordningMelding.oppfolgingsperiodeId)),
            tilordningMelding,
            ZonedDateTime.now().toEpochSecond()
        )

        processor.process(record).shouldBeInstanceOf<Retry<String, String>>()
    }

    @Test
    fun `oppfolgingsperiodeIdSerde should serialize and deserialize correctly`() {
        // Given
        val originalId = OppfolgingsperiodeId(UUID.randomUUID())
        val serde = PubliserKontorTilordningProcessor.oppfolgingsperiodeIdSerde

        // When
        val serialized = serde.serializer().serialize(topic, originalId)
        val deserialized = serde.deserializer().deserialize(topic, serialized)

        // Then
        deserialized shouldBe originalId
    }

    @Test
    fun `kontortilordningSerde should serialize and deserialize correctly`() {
        // Given
        val serde = PubliserKontorTilordningProcessor.kontortilordningSerde

        // When
        val serialized = serde.serializer().serialize(topic, tilordningMelding)
        val deserialized = serde.deserializer().deserialize(topic, serialized)

        // Then
        deserialized shouldBe tilordningMelding
    }
}