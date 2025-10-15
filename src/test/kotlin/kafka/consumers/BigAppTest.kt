package kafka.consumers

import db.table.KafkaOffsetTable
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.testing.testApplication
import kafka.consumers.TopicUtils.oppfolgingStartetMelding
import kafka.retry.TestLockProvider
import kafka.retry.library.internal.setupKafkaMock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import no.nav.db.AktorId
import no.nav.db.Ident
import no.nav.db.entity.ArbeidsOppfolgingKontorEntity
import no.nav.db.entity.ArenaKontorEntity
import no.nav.db.entity.KontorHistorikkEntity
import no.nav.db.entity.OppfolgingsperiodeEntity
import no.nav.db.table.KontorhistorikkTable
import no.nav.domain.HarSkjerming
import no.nav.domain.HarStrengtFortroligAdresse
import no.nav.domain.KontorId
import no.nav.domain.OppfolgingsperiodeId
import no.nav.http.client.AlderFunnet
import no.nav.http.client.IdentFunnet
import no.nav.http.client.GeografiskTilknytningBydelNr
import no.nav.http.client.HarStrengtFortroligAdresseFunnet
import no.nav.http.client.IdentResult
import no.nav.http.client.IdenterFunnet
import no.nav.http.client.SkjermingFunnet
import no.nav.http.client.arbeidssogerregisteret.ProfileringFunnet
import no.nav.http.client.arbeidssogerregisteret.ProfileringsResultat
import no.nav.kafka.config.configureTopology
import no.nav.kafka.consumers.EndringPaOppfolgingsBrukerProcessor
import no.nav.kafka.consumers.KontortilordningsProcessor
import no.nav.kafka.consumers.LeesahProcessor
import no.nav.kafka.consumers.SkjermingProcessor
import no.nav.services.AktivOppfolgingsperiode
import no.nav.services.AutomatiskKontorRutingService
import domain.kontorForGt.KontorForGtNrFantDefaultKontor
import no.nav.services.KontorNavnService
import no.nav.services.KontorTilhorighetService
import no.nav.services.KontorTilordningService
import no.nav.utils.flywayMigrationInTest
import no.nav.utils.randomFnr
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import services.IdentService
import services.OppfolgingsperiodeService
import topics
import utils.Outcome
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.util.UUID

class BigAppTest {

    @BeforeEach
    fun reset() {
        flywayMigrationInTest()
        transaction {
            KafkaOffsetTable.deleteAll()
        }
    }

    @Test
    fun `app should forward messages to KontorTilordning in prod`() = testApplication {
        val fnr = randomFnr()
        val aktorId = AktorId("4444447890246", Ident.HistoriskStatus.AKTIV)
        val kontor = KontorId("2232")
        val oppfolgingsperiodeId = OppfolgingsperiodeId(UUID.randomUUID())
        environment {
            config = ApplicationConfig("application.prod.yaml")
        }
        application {
            val topics = this.environment.topics()
            val oppfolgingsperiodeProvider = { _: Ident -> AktivOppfolgingsperiode(fnr, oppfolgingsperiodeId, OffsetDateTime.now()) }
            val automatiskKontorRutingService =  AutomatiskKontorRutingService(
                KontorTilordningService::tilordneKontor,
                { _, a, b-> KontorForGtNrFantDefaultKontor(kontor, b, a, GeografiskTilknytningBydelNr("3131")) },
                { AlderFunnet(40) },
                { ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER) },
                { SkjermingFunnet(HarSkjerming(false)) },
                { HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false)) },
                oppfolgingsperiodeProvider,
                { _, _ -> Outcome.Success(false)  }
            )
            val tilordningProcessor = KontortilordningsProcessor(automatiskKontorRutingService)
            val leesahProcessor = LeesahProcessor(
                automatiskKontorRutingService,
                { IdentFunnet(fnr) },
                false,
            )
            val skjermingProcessor = SkjermingProcessor(
                automatiskKontorRutingService
            )
            val endringPaaOppfolgingsBrukerProcessor = EndringPaOppfolgingsBrukerProcessor(
                oppfolgingsperiodeProvider,
                { null } // TODO: Mer realitisk test-oppsett
            )
            val identService = IdentService { IdenterFunnet(emptyList(), fnr) }
            val identendringsProcessor = IdentChangeProcessor(identService)
            val topology = configureTopology(
                this.environment,
                TestLockProvider,
                CoroutineScope(Dispatchers.IO),
                tilordningProcessor,
                leesahProcessor,
                skjermingProcessor,
                endringPaaOppfolgingsBrukerProcessor,
                identendringsProcessor,
                OppfolgingsHendelseProcessor(OppfolgingsperiodeService(identService::hentAlleIdenter ))
            )
            val (driver, inputTopics, _) = setupKafkaMock(topology,
                listOf(topics.inn.oppfolgingsHendelser.name), null
            )
            val bruker = Bruker(fnr, aktorId.value, oppfolgingsperiodeId, ZonedDateTime.now())

            inputTopics.first().pipeInput(fnr.value, oppfolgingStartetMelding(
                bruker = bruker,
            ).value())

            withClue("Skal finnes Oppfolgingsperiode på bruker") {
                transaction {
                    OppfolgingsperiodeEntity.findById(fnr.value)
                } shouldNotBe null
            }
//            withClue("Skal finnes Arenakontor på bruker") {
//                transaction {
//                    ArenaKontorEntity.findById(fnr.value)
//                } shouldNotBe null
//            }
            withClue("Skal finnes AO kontor på bruker") {
                transaction {
                    ArbeidsOppfolgingKontorEntity.findById(fnr.value)
                } shouldNotBe null
            }
            val antallHistorikkRader = transaction {
                KontorHistorikkEntity.find { KontorhistorikkTable.ident eq fnr.value }.count()
            }
            withClue("Skal finnes 2 historikkinnslag på bruker men var $antallHistorikkRader") {
                antallHistorikkRader shouldBe 2
            }
        }
    }
}
