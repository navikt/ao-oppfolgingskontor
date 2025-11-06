package kafka.consumers

import http.client.FantIkkeArenakontor
import io.kotest.matchers.types.shouldBeInstanceOf
import no.nav.db.Fnr
import no.nav.db.Ident
import no.nav.domain.OppfolgingsperiodeId
import no.nav.domain.externalEvents.OppfolgingsperiodeAvsluttet
import no.nav.domain.externalEvents.OppfolgingsperiodeEndret
import no.nav.domain.externalEvents.OppfolgingsperiodeStartet
import no.nav.kafka.processor.Skip
import org.apache.kafka.streams.processor.api.Record
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZonedDateTime
import java.util.UUID


class ArenakontorProcessorTest {

    @Test
    fun `Skal ignorere OppfolgingsperiodeAvsluttet-hendelser`() {
        val processor = ArenakontorProcessor({ FantIkkeArenakontor() }, {})
        val record = oppfolgingsperiodeAvsluttetRecord()
        val result = processor.process(record)
        result.shouldBeInstanceOf<Skip<*, *>>()
    }

    fun oppfolgingsperiodeAvsluttetRecord() : Record<Ident, OppfolgingsperiodeEndret> {
        val oppfolgingsperiode = oppfolgingsperiodeAvsluttet()
        return Record(
            oppfolgingsperiode.fnr as Ident,
            oppfolgingsperiode,
            Instant.now().toEpochMilli(),
        )
    }

    fun oppfolgingsperiodeStartet(): OppfolgingsperiodeStartet {
        return OppfolgingsperiodeStartet(
            Fnr("12211221333", Ident.HistoriskStatus.AKTIV),
            ZonedDateTime.now(),
            OppfolgingsperiodeId(UUID.randomUUID()),
            null,
            true
        )
    }

    fun oppfolgingsperiodeAvsluttet(): OppfolgingsperiodeAvsluttet {
        return OppfolgingsperiodeAvsluttet(
            Fnr("12211221333", Ident.HistoriskStatus.AKTIV),
            ZonedDateTime.now(),
            OppfolgingsperiodeId(UUID.randomUUID())
        )
    }

}