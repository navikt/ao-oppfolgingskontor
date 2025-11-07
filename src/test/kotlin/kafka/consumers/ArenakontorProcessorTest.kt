package kafka.consumers

import http.client.ArenakontorFunnet
import http.client.ArenakontorIkkeFunnet
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import no.nav.db.Ident
import no.nav.db.entity.ArenaKontorEntity
import no.nav.domain.KontorId
import no.nav.domain.OppfolgingsperiodeId
import no.nav.domain.externalEvents.OppfolgingsperiodeAvsluttet
import no.nav.domain.externalEvents.OppfolgingsperiodeEndret
import no.nav.domain.externalEvents.OppfolgingsperiodeStartet
import no.nav.http.client.IdenterFunnet
import no.nav.http.client.IdenterIkkeFunnet
import no.nav.kafka.processor.Commit
import no.nav.kafka.processor.Skip
import no.nav.services.KontorTilordningService
import no.nav.utils.flywayMigrationInTest
import no.nav.utils.randomDnr
import no.nav.utils.randomFnr
import org.apache.kafka.streams.processor.api.Record
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZonedDateTime
import java.util.*


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
            ArenaKontorEntity.findById(record.value().fnr.value) shouldBe null
        }
    }

    @Test
    fun `Skal lagre arenakontor for funnet FNR`() {
        val record = oppfolgingsperiodeStartetRecord()
        val kontorId = KontorId("1234")
        val processor = ArenakontorProcessor(
            { ArenakontorFunnet(kontorId, ZonedDateTime.now()) },
            { KontorTilordningService.tilordneKontor(it) },
            { IdenterIkkeFunnet("") })
        val result = processor.process(record)
        result.shouldBeInstanceOf<Commit<*, *>>()
        transaction {
            val arenaKontorId = ArenaKontorEntity.findById(record.value().fnr.value)!!.kontorId
            arenaKontorId shouldBe kontorId.id
        }
    }

    @Test
    fun `Ved ikke funnet kontor skal vi slå opp på andre identer`() {
        val record = oppfolgingsperiodeStartetRecord()
        val historiskDnr = randomDnr(identStatus = Ident.HistoriskStatus.HISTORISK)
        val fnr = randomFnr(identStatus = Ident.HistoriskStatus.AKTIV)
        val mottattFnrSomErUtdatert = record.value().fnr
        val hentArenaKontor = { ident: Ident ->
            when (ident) {
                mottattFnrSomErUtdatert -> ArenakontorIkkeFunnet()
                historiskDnr -> throw Exception("Skal ikke hente kontor for historisk ident som ikke er på record")
                fnr -> ArenakontorFunnet(KontorId("1234"), ZonedDateTime.now())
                else -> throw IllegalArgumentException("Uventet ident: ${ident.value}")
            }
        }

        val kontorId = KontorId("1234")
        val processor = ArenakontorProcessor(
            hentArenaKontor,
            { KontorTilordningService.tilordneKontor(it) },
            { IdenterFunnet(listOf(historiskDnr, fnr, mottattFnrSomErUtdatert), mottattFnrSomErUtdatert) })
        val result = processor.process(record)
        result.shouldBeInstanceOf<Commit<*, *>>()
        transaction {
            ArenaKontorEntity.findById(mottattFnrSomErUtdatert.value) shouldBe null

            val arenaKontorId = ArenaKontorEntity.findById(fnr.value)!!.kontorId
            arenaKontorId shouldBe kontorId.id
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