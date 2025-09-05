package kafka.consumers

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import no.nav.db.Fnr
import no.nav.db.Ident
import no.nav.domain.externalEvents.OppfolgingsperiodeStartet
import no.nav.kafka.processor.Commit
import no.nav.kafka.processor.Retry
import no.nav.kafka.processor.Skip
import no.nav.utils.flywayMigrationInTest
import no.nav.utils.randomFnr
import org.apache.kafka.streams.processor.api.Record
import org.junit.jupiter.api.Test
import services.OppfolgingsperiodeService
import java.time.Instant
import java.time.ZonedDateTime
import java.util.UUID

class OppfolgingsHendelseProcessorTest {

    @Test
    fun `skal håndtere oppfølging startet`() {
        val fnr = randomFnr()
        flywayMigrationInTest()
        val processor = OppfolgingsHendelseProcessor(OppfolgingsperiodeService())
        val record = Record(
            fnr.value,
            oppfolgingStartetMelding(fnr),
            Instant.now().toEpochMilli(),
        )

        val result = processor.process(record)

        result.shouldBeInstanceOf<Commit<Ident, OppfolgingsperiodeStartet>>()
    }

    @Test
    fun `skal håndtere oppfølging avsluttet`() {
        val fnr = randomFnr()
        flywayMigrationInTest()
        val processor = OppfolgingsHendelseProcessor(OppfolgingsperiodeService())
        val record = Record(
            fnr.value,
            oppfolgingAvsluttetMelding(fnr),
            Instant.now().toEpochMilli(),
        )

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

    fun oppfolgingStartetMelding(fnr: Fnr, periodeId: UUID = UUID.randomUUID()): String {
        return """
            {
                "hendelseType": "OPPFOLGING_STARTET",
                "oppfolgingsPeriodeId": "$periodeId",
                "startetTidspunkt": "${ZonedDateTime.now()}",
                "startetAv": "G151415",
                "startetAvType": "VEILEDER",
                "startetBegrunnelse": "ARBEIDSSOKER_REGISTRERING",
                "foretrukketArbeidsoppfolgingskontor": null,
                "arenaKontor": "4141",
                "fnr": "${fnr.value}"
            }
        """.trimIndent()
    }

    fun oppfolgingAvsluttetMelding(fnr: Fnr, periodeId: UUID = UUID.randomUUID()): String {
        return """
            {
                "fnr": "${fnr.value}",
                "hendelseType": "OPPFOLGING_AVSLUTTET",
                "oppfolgingsPeriodeId": "$periodeId",
                "startetTidspunkt": "${ZonedDateTime.now()}",
                "avsluttetTidspunkt": "${ZonedDateTime.now()}",
                "avsluttetAv": "G151415",
                "avsluttetAvType": "VEILEDER",
                "avregistreringsType": "UtmeldtEtter28Dager"
            }
        """.trimIndent()
    }
}