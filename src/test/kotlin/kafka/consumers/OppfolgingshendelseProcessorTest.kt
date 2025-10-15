package kafka.consumers

import db.entity.TidligArenaKontorEntity
import domain.ArenaKontorUtvidet
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.server.testing.*
import no.nav.db.*
import no.nav.db.Ident.HistoriskStatus.*
import no.nav.db.entity.ArenaKontorEntity
import no.nav.db.entity.OppfolgingsperiodeEntity
import no.nav.db.table.OppfolgingsperiodeTable
import no.nav.domain.KontorId
import no.nav.domain.KontorTilordning
import no.nav.domain.OppfolgingsperiodeId
import no.nav.domain.events.ArenaKontorFraOppfolgingsbrukerVedOppfolgingStartMedEtterslep
import no.nav.domain.events.OppfolgingsPeriodeStartetLokalKontorTilordning
import no.nav.domain.externalEvents.OppfolgingsperiodeStartet
import no.nav.http.client.GeografiskTilknytningBydelNr
import no.nav.http.client.IdentFunnet
import no.nav.http.client.IdenterFunnet
import no.nav.kafka.consumers.EndringPaOppfolgingsBrukerProcessor
import no.nav.kafka.processor.Commit
import no.nav.kafka.processor.Forward
import no.nav.kafka.processor.Retry
import no.nav.kafka.processor.Skip
import no.nav.services.AktivOppfolgingsperiode
import domain.kontorForGt.KontorForGtFantDefaultKontor
import no.nav.services.KontorTilordningService
import no.nav.services.NotUnderOppfolging
import no.nav.utils.flywayMigrationInTest
import no.nav.utils.hentInternId
import no.nav.utils.gittIdentIMapping
import no.nav.utils.randomFnr
import org.apache.kafka.streams.processor.api.Record
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import services.IdentService
import services.OppfolgingsperiodeService
import services.ingenSensitivitet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.*
import kotlin.test.fail

class OppfolgingshendelseProcessorTest {

    companion object {
        @BeforeAll
        @JvmStatic
        fun setup() {
            flywayMigrationInTest()
        }
    }


    fun Bruker.skalVæreUnderOppfølging(periodeId: OppfolgingsperiodeId? = null) {
        transaction {
            val entity = OppfolgingsperiodeEntity.findById(this@skalVæreUnderOppfølging.ident.value)
            entity.shouldNotBeNull()
            entity.oppfolgingsperiodeId shouldBe (periodeId ?: this@skalVæreUnderOppfølging.oppfolgingsperiodeId.value)
        }
    }

    fun Bruker.skalIkkeVæreUnderOppfølging() {
        transaction {
            val entity = OppfolgingsperiodeEntity.findById(this@skalIkkeVæreUnderOppfølging.ident.value)
            entity.shouldBeNull()
        }
    }

    fun Bruker.gittAOKontorTilordning(kontorId: KontorId) {
        KontorTilordningService.tilordneKontor(
            OppfolgingsPeriodeStartetLokalKontorTilordning(
                KontorTilordning(this.ident, kontorId, this.oppfolgingsperiodeId),
                KontorForGtFantDefaultKontor(
                    kontorId,
                    ingenSensitivitet.skjermet,
                    ingenSensitivitet.strengtFortroligAdresse,
                    geografiskTilknytningNr = GeografiskTilknytningBydelNr("313131")
                )
            )
        )
    }

    fun Bruker.gittArenaKontorTilordning(kontorId: KontorId) {
        KontorTilordningService.tilordneKontor(
            ArenaKontorFraOppfolgingsbrukerVedOppfolgingStartMedEtterslep(
                KontorTilordning(this.ident, kontorId, this.oppfolgingsperiodeId),
                OffsetDateTime.now()
            )
        )
    }

    fun Bruker.gittBrukerUnderOppfolging() {
        transaction {
            OppfolgingsperiodeTable.insert {
                it[id] = this@gittBrukerUnderOppfolging.ident.value
                it[oppfolgingsperiodeId] = this@gittBrukerUnderOppfolging.oppfolgingsperiodeId.value
                it[startDato] = ZonedDateTime.now().toOffsetDateTime()
            }
        }
    }

    fun testBruker() = Bruker(
        ident = randomFnr(),
        aktorId = "1234567890123",
        periodeStart = ZonedDateTime.now(ZoneId.of("Europe/Oslo")).minusDays(2),
        oppfolgingsperiodeId = OppfolgingsperiodeId(UUID.randomUUID()),
    )

    /* Mock at oppslag for å hente alle mappede identer bare returnerer 1 ident (happu path)  */
    fun Bruker.defaultOppfolgingsHendelseProcessor(): OppfolgingsHendelseProcessor {
        return OppfolgingsHendelseProcessor(
            OppfolgingsperiodeService { IdenterFunnet(listOf(this.ident, AktorId(this.aktorId, AKTIV)), this.ident) }
        )
    }

    @Test
    fun `Skal lagre ny oppfolgingsperiode når oppfolgingsperiode-startet`() = testApplication {
        val bruker = testBruker()
        val oppfolgingshendelseProcessor = bruker.defaultOppfolgingsHendelseProcessor()
        val record = oppfolgingStartetMelding(bruker)

        val result = oppfolgingshendelseProcessor.process(record)

        result.shouldBeInstanceOf<Forward<Ident, OppfolgingsperiodeStartet>>()
        bruker.skalVæreUnderOppfølging()
    }

    @Test
    fun `Skal slette periode når avslutningsmelding kommer `() = testApplication {
        val bruker = testBruker()
        val oppfolgingshendelseProcessor = bruker.defaultOppfolgingsHendelseProcessor()
        val hendelseStartResult = oppfolgingshendelseProcessor.process(oppfolgingStartetMelding(bruker))
        hendelseStartResult.shouldBeInstanceOf<Forward<*, *>>()

        val sluttDato = ZonedDateTime.now()
        val hendelseResult = oppfolgingshendelseProcessor.process(oppfolgingAvsluttetMelding(bruker, sluttDato))

        hendelseResult.shouldBeInstanceOf<Commit<*, *>>()
        bruker.skalIkkeVæreUnderOppfølging()
    }

    @Test
    fun `Skal hoppe over avslutt-melding når bruker ikke er under oppfølging`() = testApplication {
        val bruker = testBruker()
        val periodeSlutt = ZonedDateTime.now().minusDays(1)

        val consumer = bruker.defaultOppfolgingsHendelseProcessor()
        val result = consumer.process(oppfolgingAvsluttetMelding(bruker, sluttDato = periodeSlutt))

        result.shouldBeInstanceOf<Skip<*, *>>()
        bruker.skalIkkeVæreUnderOppfølging()
    }

    @Test
    fun `Skal slette eksisterende oppfolgingsperiode når perioden er avsluttet`() = testApplication {
        val bruker = testBruker()
        val periodeSlutt = ZonedDateTime.now().minusDays(1)

        val consumer = bruker.defaultOppfolgingsHendelseProcessor()
        val startPeriodeRecord = oppfolgingStartetMelding(bruker)
        val avsluttetNyerePeriodeRecord = oppfolgingAvsluttetMelding(
            bruker.copy(periodeStart = bruker.periodeStart.plusSeconds(1)), sluttDato = periodeSlutt
        )

        consumer.process(startPeriodeRecord)
        val result = consumer.process(avsluttetNyerePeriodeRecord)

        result.shouldBeInstanceOf<Commit<*, *>>()
        bruker.skalIkkeVæreUnderOppfølging()
    }

    @Test
    fun `Skal hoppe over start-melding hvis den er på en gammel periode`() = testApplication {
        val bruker = testBruker()
        val consumer = bruker.defaultOppfolgingsHendelseProcessor()
        val startPeriodeRecord = oppfolgingStartetMelding(bruker)
        val startGammelPeriodeRecord = oppfolgingStartetMelding(
            bruker.copy(
                oppfolgingsperiodeId = OppfolgingsperiodeId(UUID.randomUUID()),
                periodeStart = bruker.periodeStart.minusSeconds(1),
            )
        )

        consumer.process(startPeriodeRecord)
        val processingResult = consumer.process(startGammelPeriodeRecord)

        processingResult.shouldBeInstanceOf<Skip<*, *>>()
        bruker.skalVæreUnderOppfølging()
    }

    @Test
    fun `Skal slette gammel periode ved melding om start på nyere periode`() = testApplication {
        val bruker = testBruker()
        val nyerePeriodeId = UUID.randomUUID()
        val nyereStartDato = bruker.periodeStart.plusSeconds(1)
        val consumer = bruker.defaultOppfolgingsHendelseProcessor()
        val startPeriodeRecord = oppfolgingStartetMelding(bruker)
        val startNyerePeriodeRecord = oppfolgingStartetMelding(
            bruker.copy(
                oppfolgingsperiodeId = OppfolgingsperiodeId(nyerePeriodeId),
                periodeStart = nyereStartDato,
            )
        )
        consumer.process(startPeriodeRecord)

        val processingResult = consumer.process(startNyerePeriodeRecord)

        processingResult.shouldBeInstanceOf<Forward<*, *>>()
        processingResult.forwardedRecord.key() shouldBe bruker.ident
        processingResult.forwardedRecord.value() shouldBe OppfolgingsperiodeStartet(
            bruker.ident,
            nyereStartDato,
            OppfolgingsperiodeId(nyerePeriodeId),
            null,
            true
        )
        processingResult.topic shouldBe null
        transaction {
            val oppfolgingForBruker = OppfolgingsperiodeEntity.findById(bruker.ident.value)
            oppfolgingForBruker.shouldNotBeNull()
            oppfolgingForBruker.oppfolgingsperiodeId shouldBe nyerePeriodeId
            withClue("startDato lest fra db: ${oppfolgingForBruker.startDato.toInstant()} skal være lik input startDato: ${nyereStartDato.toInstant()}") {
                // Truncated always rounds down, therefore we add 500 nanos to make it behave like actual rounding like done when
                // too high precision is inserted into the db
                oppfolgingForBruker.startDato.toInstant() shouldBe nyereStartDato.toInstant().plusNanos(500)
                    .truncatedTo(ChronoUnit.MICROS)
            }
        }

    }

    @Test
    fun `Skal slette gammel periode ved melding om slutt på nyere periode`() = testApplication {
        val bruker = testBruker()
        val nyereStartDato = bruker.periodeStart.plusSeconds(1)
        val periodeSlutt = nyereStartDato.plusSeconds(1)
        val oppfolgingsHendelseProcessor = bruker.defaultOppfolgingsHendelseProcessor()
        val startPeriodeRecord = oppfolgingStartetMelding(bruker)
        val sluttNyerePeriodeRecord = oppfolgingAvsluttetMelding(
            bruker.copy(
                oppfolgingsperiodeId = OppfolgingsperiodeId(UUID.randomUUID()),
                periodeStart = nyereStartDato,
            ), sluttDato = periodeSlutt
        )
        oppfolgingsHendelseProcessor.process(startPeriodeRecord)

        val processingResult = oppfolgingsHendelseProcessor.process(sluttNyerePeriodeRecord)

        processingResult.shouldBeInstanceOf<Commit<*, *>>()
        bruker.skalIkkeVæreUnderOppfølging()

    }

    @Test
    fun `Skal retry-e på deserialiseringsfeil`() {
        val bruker = testBruker()
        val oppfolgingsHendelseProcessor = bruker.defaultOppfolgingsHendelseProcessor()

        val result = oppfolgingsHendelseProcessor.process(
            Record(
                bruker.aktorId,
                """{ "lol": "lal" }""",
                Instant.now().toEpochMilli()
            )
        )

        result.shouldBeInstanceOf<Retry<*, *>>()
        result.reason shouldBe """
            Kunne ikke behandle oppfolgingshendelse - <Ukjent hendelsetype>: Class discriminator was missing and no default serializers were registered in the polymorphic scope of 'OppfolgingsHendelseDto'.
            JSON input: {"lol":"lal"}
        """.trimIndent()
    }

    @Test
    fun `Skal hoppe over start-melding når perioden er slettet`() {
        val bruker = testBruker()
        val oppfolgingshendelseProcessor = bruker.defaultOppfolgingsHendelseProcessor()
        val sluttDato = ZonedDateTime.now().plusDays(1)
        val startmelding = oppfolgingStartetMelding(bruker)
        val stoppmelding = oppfolgingAvsluttetMelding(bruker, sluttDato)
        oppfolgingshendelseProcessor.process(startmelding).shouldBeInstanceOf<Forward<*, *>>()
        bruker.gittAOKontorTilordning(KontorId("1199"))
        oppfolgingshendelseProcessor.process(stoppmelding).shouldBeInstanceOf<Commit<*, *>>()

        val resultStartMeldingPåNytt = oppfolgingshendelseProcessor.process(startmelding)
        val resultStoppMeldingPåNytt = oppfolgingshendelseProcessor.process(stoppmelding)

        resultStartMeldingPåNytt.shouldBeInstanceOf<Skip<*, *>>()
        resultStoppMeldingPåNytt.shouldBeInstanceOf<Skip<*, *>>()
    }

    @Test
    fun `Skal forwarde start-melding om på allerede lagrede oppfølgingsperioder hvis kontortilordning (ao-kontor) ikke er gjort`() {
        val bruker = testBruker()
        bruker.gittBrukerUnderOppfolging()
        bruker.gittArenaKontorTilordning(KontorId("1199"))
        val oppfolgingshendelseProcessor = bruker.defaultOppfolgingsHendelseProcessor()
        val startmelding = oppfolgingStartetMelding(bruker)

        val result = oppfolgingshendelseProcessor.process(startmelding)

        result.shouldBeInstanceOf<Forward<*, *>>()
    }

    @Test
    fun `Skal hoppe over start-melding hvis perioden og kontortilordning (ao-kontor) finnes`() {
        val bruker = testBruker()
        bruker.gittBrukerUnderOppfolging()
        bruker.gittAOKontorTilordning(KontorId("1199"))
        val oppfolgingshendelseProcessor = bruker.defaultOppfolgingsHendelseProcessor()
        val startmelding = oppfolgingStartetMelding(bruker)

        val result = oppfolgingshendelseProcessor.process(startmelding)

        result.shouldBeInstanceOf<Skip<*, *>>()
    }

    @Disabled
    @Test
    fun `Skal hoppe over start-melding men oppdatere kontor når perioden er allerede er startet (men ikke avsluttet)`() {
        val bruker = testBruker()
        val arenaKontor = KontorId("1122")
        val oppfolgingshendelseProcessor = bruker.defaultOppfolgingsHendelseProcessor()
        val startmelding = oppfolgingStartetMelding(bruker, arenaKontor)
        oppfolgingshendelseProcessor.process(startmelding).shouldBeInstanceOf<Forward<*, *>>()
        bruker.gittAOKontorTilordning(KontorId("1199"))

        /* Hvis arenakontor ikke allerede er satt skal det settes */
        oppfolgingshendelseProcessor.process(startmelding).shouldBeInstanceOf<Commit<*, *>>()

        bruker.gittArenaKontorTilordning(KontorId("1122"))
        /* Når arena-kontoret er satt kan man skippe melding */
        oppfolgingshendelseProcessor.process(startmelding).shouldBeInstanceOf<Skip<*, *>>()

        bruker.skalHaArenaKontor(arenaKontor.id)
    }

    @Test
    fun `Skal hoppe over avslutt-melding for en periode vi ikke visste om`() {
        val bruker = testBruker()
        val oppfolgingshendelseProcessor = bruker.defaultOppfolgingsHendelseProcessor()
        val record = TopicUtils.oppfolgingAvsluttetMelding(bruker, ZonedDateTime.now())

        val result = oppfolgingshendelseProcessor.process(record)

        result.shouldBeInstanceOf<Skip<Ident, OppfolgingsperiodeStartet>>()
    }

    fun gittBrukerMedTidligArenaKontor(ident: Ident, sistLagretArenaKontor: String, arenaKontor: String) {
        val sistLagreArenaKontor = ArenaKontorUtvidet(
            kontorId = KontorId(sistLagretArenaKontor),
            oppfolgingsperiodeId = OppfolgingsperiodeId(UUID.randomUUID()),
            sistEndretDatoArena = OffsetDateTime.now().minusSeconds(1)
        )
        val endringPaOppfolgingsBrukerProcessor = EndringPaOppfolgingsBrukerProcessor(
            { NotUnderOppfolging },
            { sistLagreArenaKontor })
        endringPaOppfolgingsBrukerProcessor.process(
            TopicUtils.endringPaaOppfolgingsBrukerMessage(
                ident,
                arenaKontor,
                Instant.now().atZone(ZoneId.of("Europe/Oslo")).toOffsetDateTime(),
                no.nav.kafka.consumers.FormidlingsGruppe.ARBS,
                no.nav.kafka.consumers.Kvalifiseringsgruppe.IKVAL
            )
        )
    }

    fun Bruker.skalHaTidligArenaKontor(foreventetKontor: String?, annenIdent: Ident? = null) {
        val identMedArenaKontor = annenIdent ?: this.ident
        val arenaKontor = transaction {
            TidligArenaKontorEntity.findById(identMedArenaKontor.value)?.kontorId
        }
        withClue("Forventet bruker skulle ha forhåndslagret arenakontor for oppfølging-start: $foreventetKontor men hadde $arenaKontor") {
            arenaKontor shouldBe foreventetKontor
        }
    }

    fun Bruker.skalHaArenaKontor(foreventetKontor: String?) {
        val arenaKontor = transaction {
            ArenaKontorEntity.findById(this@skalHaArenaKontor.ident.value)?.kontorId
        }
        withClue("Forventet bruker skulle arenakontor: $foreventetKontor men hadde $arenaKontor") {
            arenaKontor shouldBe foreventetKontor
        }
    }

    @Test
    fun `Skal finne tidlig-arena-kontor selv om det er lagret på en annen av brukers identer`() {
        val bruker = testBruker()
        gittIdentIMapping(bruker.ident)
        val annenIdent = randomFnr()
        val internId = hentInternId(bruker.ident)
        gittIdentIMapping(ident = annenIdent, internIdent = internId)
        val arenaKontorVeilarboppfolging = "4141"
        val arenaKontor = "4142"
        val hendelseProcessor = bruker.defaultOppfolgingsHendelseProcessor()
        gittBrukerMedTidligArenaKontor(annenIdent, arenaKontorVeilarboppfolging, arenaKontor)
        bruker.skalHaTidligArenaKontor(arenaKontor, annenIdent)

        val result = hendelseProcessor.process(TopicUtils.oppfolgingStartetMelding(bruker))

        result.shouldBeInstanceOf<Forward<Ident, OppfolgingsperiodeStartet>>()
        val videresendtMelding = result.forwardedRecord.value()
        videresendtMelding.arenaKontorFraOppfolgingsbrukerTopic!!.kontor.id shouldBe arenaKontor
    }

    @Test
    fun `Skal slette tidlig-arena-kontor hvis det blir brukt`() {
        val bruker = testBruker()
        gittIdentIMapping(bruker.ident)
        val arenaKontorVeilarboppfolging = "4141"
        val arenaKontor = "4142"
        val hendelseProcessor = bruker.defaultOppfolgingsHendelseProcessor()
        gittBrukerMedTidligArenaKontor(bruker.ident, arenaKontorVeilarboppfolging, arenaKontor)
        bruker.skalHaTidligArenaKontor(arenaKontor)

        hendelseProcessor.process(TopicUtils.oppfolgingStartetMelding(bruker))

        bruker.skalHaTidligArenaKontor(null)
    }

    class Asserts(val service: OppfolgingsperiodeService, val bruker: Bruker) {
        fun skalVæreUnderOppfolging() = service.getCurrentOppfolgingsperiode(IdentFunnet(bruker.ident))
            .shouldBeInstanceOf<AktivOppfolgingsperiode>()

        fun skalIkkeVæreUnderOppfolging() = service.getCurrentOppfolgingsperiode(IdentFunnet(bruker.ident))
            .shouldBeInstanceOf<NotUnderOppfolging>()
    }

    @Test
    fun `Skal slette periode ved avslutt-melding (på ny ident)`() = testApplication {
        val aktivtDnr = Dnr("52105678901", AKTIV)
        val historiskDnr = Dnr(aktivtDnr.value, HISTORISK)
        val aktorId = AktorId("1234567890123", AKTIV)
        val fnr = randomFnr()
        val bruker = Bruker(
            ident = fnr,
            aktorId = aktorId.value,
            periodeStart = ZonedDateTime.now(ZoneId.of("Europe/Oslo")).minusDays(2),
            oppfolgingsperiodeId = OppfolgingsperiodeId(UUID.randomUUID()),
        )
        val brukerMedDnr = bruker.copy(ident = aktivtDnr)
        val brukerMedFnr = bruker.copy(ident = fnr)
        application {
            gittIdentIMapping(aktivtDnr)
            val identService = IdentService { input ->
                val inputIdent = Ident.validateOrThrow(input, UKJENT)
                // I denne testen simulerer vi at vi får inn en melding med dnr først, derfor returneres ikke fnr når inputIdent er dnr
                when (inputIdent) {
                    is Dnr -> IdenterFunnet(listOf(aktorId, aktivtDnr), inputIdent)
                    is Fnr -> IdenterFunnet(listOf(aktorId, historiskDnr, fnr), inputIdent)
                    is AktorId -> IdenterFunnet(listOf(aktorId, historiskDnr, fnr), inputIdent)
                    is Npid -> fail("LOL")
                }
            }
            val identChangeProcessor = IdentChangeProcessor(identService)
            val oppfolgingsPeriodeService = OppfolgingsperiodeService(identService::hentAlleIdenter)
            val oppfolgingshendelseProcessor = OppfolgingsHendelseProcessor(oppfolgingsPeriodeService)
            val startResult = oppfolgingshendelseProcessor
                .process(oppfolgingStartetMelding(brukerMedDnr))
            startResult.shouldBeInstanceOf<Forward<*, *>>()
            val brukerMedDnrAsserts = Asserts(oppfolgingsPeriodeService, brukerMedDnr)
            val brukerMedFnrAsserts = Asserts(oppfolgingsPeriodeService, brukerMedFnr)

            brukerMedDnrAsserts.skalVæreUnderOppfolging()

            /* Marker dnr som historisk */
            identChangeProcessor.process(
                TopicUtils.aktorV2Message(
                    aktorId.value,
                    listOf(aktorId, historiskDnr, fnr),
                )
            )

            /* Når man har mottatt ident-endring skal begge identene
            * svare at bruker er under oppfølging */
            brukerMedDnrAsserts.skalVæreUnderOppfolging()
            brukerMedFnrAsserts.skalVæreUnderOppfolging()

            val sluttDato = ZonedDateTime.now()
            val avsluttMedNyIdentResult = oppfolgingshendelseProcessor.process(
                oppfolgingAvsluttetMelding(brukerMedFnr, sluttDato)
            )

            avsluttMedNyIdentResult.shouldBeInstanceOf<Commit<*, *>>()
            brukerMedDnrAsserts.skalIkkeVæreUnderOppfolging()
            brukerMedFnrAsserts.skalIkkeVæreUnderOppfolging()
        }
    }

    fun oppfolgingStartetMelding(bruker: Bruker, arenaKontor: KontorId = KontorId("4141")): Record<String, String> =
        TopicUtils.oppfolgingStartetMelding(bruker, arenaKontor)

    fun oppfolgingAvsluttetMelding(bruker: Bruker, sluttDato: ZonedDateTime): Record<String, String> =
        TopicUtils.oppfolgingAvsluttetMelding(bruker, sluttDato)
}
