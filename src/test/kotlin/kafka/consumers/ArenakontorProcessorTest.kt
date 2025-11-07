package kafka.consumers

import http.client.ArenakontorFunnet
import http.client.ArenakontorIkkeFunnet
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.types.shouldBeInstanceOf
import no.nav.db.Fnr
import no.nav.db.Ident
import no.nav.db.entity.ArenaKontorEntity
import no.nav.db.table.ArenaKontorTable
import no.nav.domain.OppfolgingsperiodeId
import no.nav.domain.externalEvents.OppfolgingsperiodeAvsluttet
import no.nav.domain.externalEvents.OppfolgingsperiodeEndret
import no.nav.domain.externalEvents.OppfolgingsperiodeStartet
import no.nav.http.client.IdentFunnet
import no.nav.http.client.IdenterFunnet
import no.nav.http.client.IdenterIkkeFunnet
import no.nav.kafka.processor.Skip
import no.nav.services.KontorTilordningService
import no.nav.utils.flywayMigrationInTest
import no.nav.utils.randomFnr
import org.apache.kafka.streams.processor.api.Record
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZonedDateTime
import java.util.UUID


class ArenakontorProcessorTest {

    companion object {
        @BeforeAll
        @JvmStatic
        fun setup() {
            flywayMigrationInTest()
        }
    }

    @Test
    fun `Skal ignorere OppfolgingsperiodeAvsluttet-hendelser`() {
        val processor = ArenakontorProcessor(
            { ArenakontorIkkeFunnet() },
            { KontorTilordningService.tilordneKontor(it) },
            { IdenterIkkeFunnet("") })
        val record = oppfolgingsperiodeAvsluttetRecord()
        val result = processor.process(record)
        result.shouldBeInstanceOf<Skip<*, *>>()
        transaction {
            ArenaKontorEntity.findById(record.value().fnr.value)
        }
    }

    @Test
    fun `Skal lagre arenakontor for funnet FNR`() {

        val record = oppfolgingsperiodeAvsluttetRecord()
        val processor = ArenakontorProcessor(
            { ArenakontorFunnet },
            { KontorTilordningService.tilordneKontor(it) },
            { IdenterIkkeFunnet("") })
        val result = processor.process(record)
        result.shouldBeInstanceOf<Skip<*, *>>()
        transaction {
            ArenaKontorEntity.findById(record.value().fnr.value)
        }
    }

    fun oppfolgingsperiodeAvsluttetRecord(): Record<Ident, OppfolgingsperiodeEndret> {
        val oppfolgingsperiode = oppfolgingsperiodeAvsluttet()
        return Record(
            oppfolgingsperiode.fnr as Ident,
            oppfolgingsperiode,
            Instant.now().toEpochMilli(),
        )
    }

    fun oppfolgingsperiodeStartetRecord(): Record<Ident, OppfolgingsperiodeEndret> {
        val oppfolgingsperiode = oppfolgingsperiodeStartet()
        return Record(
            oppfolgingsperiode.fnr as Ident,
            oppfolgingsperiode,
            Instant.now().toEpochMilli(),
        )
    }

    fun oppfolgingsperiodeStartet(): OppfolgingsperiodeStartet {
        return OppfolgingsperiodeStartet(
            randomFnr(),
            ZonedDateTime.now(),
            OppfolgingsperiodeId(UUID.randomUUID()),
            null,
            true
        )
    }

    fun oppfolgingsperiodeAvsluttet(): OppfolgingsperiodeAvsluttet {
        return OppfolgingsperiodeAvsluttet(
            randomFnr(),
            ZonedDateTime.now(),
            OppfolgingsperiodeId(UUID.randomUUID())
        )
    }

}