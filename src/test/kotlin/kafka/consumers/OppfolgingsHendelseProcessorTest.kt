package kafka.consumers

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import no.nav.db.Fnr
import no.nav.db.Ident
import no.nav.domain.OppfolgingsperiodeId
import no.nav.domain.externalEvents.OppfolgingsperiodeStartet
import no.nav.kafka.processor.Commit
import no.nav.kafka.processor.Forward
import no.nav.kafka.processor.Retry
import no.nav.kafka.processor.Skip
import no.nav.utils.flywayMigrationInTest
import no.nav.utils.randomFnr
import org.apache.kafka.streams.processor.api.Record
import org.junit.jupiter.api.Test
import services.OppfolgingsperiodeService
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

class OppfolgingsHendelseProcessorTest {
    fun testBruker() = Bruker(
        fnr = randomFnr(),
        aktorId = "1234567890123",
        periodeStart = ZonedDateTime.now(ZoneId.of("Europe/Oslo")).minusDays(2),
        oppfolgingsperiodeId = OppfolgingsperiodeId(UUID.randomUUID()),
    )

    @Test
    fun `skal håndtere oppfølging startet`() {
        val bruker = testBruker()
        flywayMigrationInTest()
        val processor = OppfolgingsHendelseProcessor(OppfolgingsperiodeService())
        val record = TopicUtils.oppfolgingStartetMelding(bruker)

        val result = processor.process(record)

        result.shouldBeInstanceOf<Forward<Ident, OppfolgingsperiodeStartet>>()
    }

    @Test
    fun `skal håndtere oppfølging avsluttet`() {
        val bruker = testBruker()
        flywayMigrationInTest()
        val processor = OppfolgingsHendelseProcessor(OppfolgingsperiodeService())
        val record = TopicUtils.oppfolgingAvsluttetMelding(bruker, ZonedDateTime.now())

        val result = processor.process(record)

        result.shouldBeInstanceOf<Skip<Ident, OppfolgingsperiodeStartet>>()
    }

    @Test
    fun `skal håndtere deserialiseringsfeil`() {
        val consumer = OppfolgingsHendelseProcessor(OppfolgingsperiodeService())

        val result = consumer.process(Record("123", """{ "lol": "lal" }""", Instant.now().toEpochMilli()))

        result.shouldBeInstanceOf<Retry<*, *>>()
        result.reason shouldBe """
            Kunne ikke behandle oppfolgingshendelse - <Ukjent hendelsetype>: Class discriminator was missing and no default serializers were registered in the polymorphic scope of 'OppfolgingsHendelseDto'.
            JSON input: {"lol":"lal"}
        """.trimIndent()
    }
}