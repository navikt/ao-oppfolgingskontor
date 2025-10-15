package kafka.consumers

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeTypeOf
import io.ktor.server.testing.testApplication
import no.nav.db.Fnr
import no.nav.db.Ident
import no.nav.db.entity.ArbeidsOppfolgingKontorEntity
import no.nav.db.entity.GeografiskTilknyttetKontorEntity
import no.nav.db.table.ArbeidsOppfolgingKontorTable
import no.nav.db.table.GeografiskTilknytningKontorTable
import no.nav.domain.HarSkjerming
import no.nav.domain.HarStrengtFortroligAdresse
import no.nav.domain.KontorEndringsType
import no.nav.domain.KontorId
import no.nav.domain.KontorTilordning
import no.nav.domain.OppfolgingsperiodeId
import no.nav.domain.Sensitivitet
import no.nav.domain.events.GTKontorEndret
import no.nav.domain.events.OppfolgingsPeriodeStartetLokalKontorTilordning
import no.nav.domain.externalEvents.AdressebeskyttelseEndret
import no.nav.domain.externalEvents.BostedsadresseEndret
import no.nav.http.client.IdentFunnet
import no.nav.http.client.GeografiskTilknytningBydelNr
import no.nav.http.client.GeografiskTilknytningNr
import no.nav.http.client.GtForBrukerSuccess
import no.nav.http.client.GtNummerForBrukerFunnet
import no.nav.http.client.GtType
import no.nav.http.client.HarStrengtFortroligAdresseFunnet
import no.nav.http.client.HarStrengtFortroligAdresseResult
import no.nav.http.client.SkjermingFunnet
import no.nav.kafka.consumers.LeesahProcessor
import no.nav.kafka.processor.Retry
import no.nav.person.pdl.leesah.adressebeskyttelse.Gradering
import no.nav.services.AktivOppfolgingsperiode
import no.nav.services.AutomatiskKontorRutingService
import no.nav.services.AutomatiskKontorRutingService.Companion.VIKAFOSSEN
import no.nav.services.KontorForGtNrFantDefaultKontor
import no.nav.services.KontorForGtFeil
import no.nav.services.KontorForGtResultat
import no.nav.services.KontorTilordningService
import no.nav.utils.flywayMigrationInTest
import no.nav.utils.randomFnr
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import utils.Outcome
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.util.UUID

class LeesahProcessorTest {

    @Test
    fun `skal sjekke gt kontor på nytt ved bostedsadresse endret`() = testApplication {
        val fnr = randomFnr()
        val gammeltKontorId = "1234"
        val nyKontorId = "5678"
        application {
            flywayMigrationInTest()
            gittNåværendeGtKontor(fnr, KontorId(gammeltKontorId))
            val automatiskKontorRutingService = gittRutingServiceMedGtKontor(KontorId(nyKontorId))
            val leesahProcessor = LeesahProcessor(automatiskKontorRutingService, { IdentFunnet(fnr) }, false)

            leesahProcessor.handterLeesahHendelse(BostedsadresseEndret(fnr))

            transaction {
                val kontorEtterEndirng = GeografiskTilknyttetKontorEntity[fnr.value]
                kontorEtterEndirng.kontorId shouldBe nyKontorId
            }
        }
    }

    @Test
    fun `skal sette både gt-kontor og ao-kontor ved addressebeskyttelse endret hvis det er nytt kontor`() = testApplication {
        val fnr = randomFnr()
        val gammeltKontorId = "1234"
        val nyKontorId = "5678"
        application {
            flywayMigrationInTest()
            gittNåværendeGtKontor(fnr, KontorId(gammeltKontorId))
            val automatiskKontorRutingService = defaultAutomatiskKontorRutingService(
                { a, b, c -> KontorForGtNrFantDefaultKontor(KontorId(nyKontorId), c, b, GeografiskTilknytningBydelNr("3131")) },
                { ident -> HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(true)) }
            )
            val leesahProcessor = LeesahProcessor(automatiskKontorRutingService, { IdentFunnet(fnr) }, false)

            leesahProcessor.handterLeesahHendelse(AdressebeskyttelseEndret(fnr, Gradering.STRENGT_FORTROLIG))

            transaction {
                val gtKontorEtterEndring = GeografiskTilknyttetKontorEntity[fnr.value]
                gtKontorEtterEndring.kontorId shouldBe VIKAFOSSEN.id

                val aoKontorEtterEndirng = ArbeidsOppfolgingKontorEntity[fnr.value]
                aoKontorEtterEndirng.kontorId shouldBe VIKAFOSSEN.id
            }
        }
    }

    @Test
    fun `skal ikke sette ao-kontor men gt-kontor ved addressebeskyttelse endret hvis det er nytt kontor`() = testApplication {
        val fnr = randomFnr()
        val gammelKontorId = "1234"
        val nyKontorId = "5678"
        application {
            flywayMigrationInTest()
            gittNåværendeAOKontor(fnr, KontorId(gammelKontorId))
            gittNåværendeGtKontor(fnr, KontorId(gammelKontorId))
            val automatiskKontorRutingService = gittRutingServiceMedGtKontor(KontorId(nyKontorId))
            val leesahProcessor = LeesahProcessor(automatiskKontorRutingService, { IdentFunnet(fnr) }, false)

            leesahProcessor.handterLeesahHendelse(AdressebeskyttelseEndret(fnr, Gradering.UGRADERT))

            transaction {
                val gtKontorEtterEndring = GeografiskTilknyttetKontorEntity[fnr.value]
                gtKontorEtterEndring.kontorId shouldBe nyKontorId

                val aoKontorEtterEndirng = ArbeidsOppfolgingKontorEntity[fnr.value]
                aoKontorEtterEndirng.kontorId shouldBe gammelKontorId
            }
        }
    }

    @Test
    fun `skal håndtere at gt-provider returnerer GTKontorFeil`() = testApplication {
        val fnr = randomFnr()
        val automatiskKontorRutingService = defaultAutomatiskKontorRutingService(
            { a, b, c -> KontorForGtFeil("Noe gikk galt") }
        )
        val leesahProcessor = LeesahProcessor(automatiskKontorRutingService, { IdentFunnet(fnr) }, false)

        val resultat = leesahProcessor.handterLeesahHendelse(BostedsadresseEndret(fnr))

        resultat.shouldBeTypeOf<Retry<String, String>>()
        resultat.reason shouldBe "Kunne ikke håndtere endring i bostedsadresse pga feil ved henting av gt-kontor: Noe gikk galt"
    }

    @Test
    fun `skal håndtere at gt-provider kaster throwable`() = testApplication {
        val fnr = randomFnr()
        val automatiskKontorRutingService = defaultAutomatiskKontorRutingService(
            { a, b, c -> throw Throwable("Noe gikk galt") }
        )
        val leesahProcessor = LeesahProcessor(automatiskKontorRutingService, { IdentFunnet(fnr) }, false)

        val resultat = leesahProcessor.handterLeesahHendelse(BostedsadresseEndret(fnr))

        resultat.shouldBeTypeOf<Retry<String, String>>()
        resultat.reason shouldBe "Uventet feil ved håndtering av endring i bostedsadresse: Noe gikk galt"
    }

    private fun defaultAutomatiskKontorRutingService(
        gtProvider: suspend (ident: Ident, strengtFortroligAdresse: HarStrengtFortroligAdresse, skjermet: HarSkjerming) -> KontorForGtResultat,
        strengtFortroligAdresseProvider: suspend (ident: Ident) -> HarStrengtFortroligAdresseResult = { HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false)) }
    ): AutomatiskKontorRutingService {
        return AutomatiskKontorRutingService(
            KontorTilordningService::tilordneKontor,
            gtKontorProvider = gtProvider,
            aldersProvider = { throw Throwable("Denne skal ikke brukes") },
            profileringProvider = { throw Throwable("Denne skal ikke brukes") },
            erSkjermetProvider = { SkjermingFunnet(HarSkjerming(false)) },
            harStrengtFortroligAdresseProvider = strengtFortroligAdresseProvider,
            isUnderOppfolgingProvider = { AktivOppfolgingsperiode(Fnr("66666666666", Ident.HistoriskStatus.AKTIV), OppfolgingsperiodeId(UUID.randomUUID()), OffsetDateTime.now()) },
            harTilordnetKontorForOppfolgingsperiodeStartetProvider = { _, _ -> Outcome.Success(false)  }
        )
    }

    private fun gittRutingServiceMedGtKontor(kontorId: KontorId): AutomatiskKontorRutingService {
        return defaultAutomatiskKontorRutingService(
            { a, b, c -> KontorForGtNrFantDefaultKontor(kontorId, c, b, GeografiskTilknytningBydelNr("3131")) }
        )
    }

    private fun gittNåværendeGtKontor(fnr: Fnr, kontorId: KontorId) {
        val oppfolgingsperiodeId = OppfolgingsperiodeId(UUID.randomUUID())
        KontorTilordningService.tilordneKontor(
            GTKontorEndret(
                kontorTilordning = KontorTilordning(fnr, kontorId, oppfolgingsperiodeId),
                kontorEndringsType = KontorEndringsType.EndretBostedsadresse,
                gt = GtNummerForBrukerFunnet(GeografiskTilknytningBydelNr("3131"))
            )
        )
    }

    private fun gittNåværendeAOKontor(fnr: Fnr, kontorId: KontorId) {
        val oppfolgingsperiodeId = OppfolgingsperiodeId(UUID.randomUUID())
        KontorTilordningService.tilordneKontor(
            OppfolgingsPeriodeStartetLokalKontorTilordning(
                kontorTilordning = KontorTilordning(fnr, kontorId, oppfolgingsperiodeId),
                kontorForGt = KontorForGtNrFantDefaultKontor(
                    kontorId,
                    HarSkjerming(false),
                    HarStrengtFortroligAdresse(false),
                    geografiskTilknytningNr = GeografiskTilknytningBydelNr("313121")
                ),
            )
        )
    }
}