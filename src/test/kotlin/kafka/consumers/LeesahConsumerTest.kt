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
import no.nav.domain.KontorId
import no.nav.domain.OppfolgingsperiodeId
import no.nav.domain.externalEvents.AdressebeskyttelseEndret
import no.nav.domain.externalEvents.BostedsadresseEndret
import no.nav.http.client.FnrFunnet
import no.nav.http.client.GeografiskTilknytningBydelNr
import no.nav.http.client.HarStrengtFortroligAdresseFunnet
import no.nav.http.client.SkjermingFunnet
import no.nav.kafka.consumers.LeesahConsumer
import no.nav.kafka.processor.Retry
import no.nav.person.pdl.leesah.adressebeskyttelse.Gradering
import no.nav.services.AktivOppfolgingsperiode
import no.nav.services.AutomatiskKontorRutingService
import no.nav.services.KontorForGtNrFantDefaultKontor
import no.nav.services.KontorForGtNrFeil
import no.nav.services.KontorForGtNrResultat
import no.nav.services.KontorTilordningService
import no.nav.utils.flywayMigrationInTest
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.util.UUID

class LeesahConsumerTest {

    @Test
    fun `skal sjekke gt kontor på nytt ved bostedsadresse endret`() = testApplication {
        val fnr = Fnr("12345678901")
        val gammeltKontorId = "1234"
        val nyKontorId = "5678"
        application {
            flywayMigrationInTest()
            gittNåværendeGtKontor(fnr, KontorId(gammeltKontorId))
            val automatiskKontorRutingService = gittRutingServiceMedGtKontor(KontorId(nyKontorId))
            val leesahConsumer = LeesahConsumer(automatiskKontorRutingService, { FnrFunnet(fnr) })

            leesahConsumer.handterLeesahHendelse(BostedsadresseEndret(fnr))

            transaction {
                val kontorEtterEndirng = GeografiskTilknyttetKontorEntity[fnr.value]
                kontorEtterEndirng.kontorId shouldBe nyKontorId
            }
        }
    }

    @Test
    fun `skal sette både gt-kontor og ao-kontor ved addressebeskyttelse endret hvis det er nytt kontor`() = testApplication {
        val fnr = Fnr("12345678920")
        val gammeltKontorId = "1234"
        val nyKontorId = "5678"
        application {
            flywayMigrationInTest()
            gittNåværendeGtKontor(fnr, KontorId(gammeltKontorId))
            val automatiskKontorRutingService = gittRutingServiceMedGtKontor(KontorId(nyKontorId))
            val leesahConsumer = LeesahConsumer(automatiskKontorRutingService, { FnrFunnet(fnr) })

            leesahConsumer.handterLeesahHendelse(AdressebeskyttelseEndret(fnr, Gradering.STRENGT_FORTROLIG))

            transaction {
                val gtKontorEtterEndring = GeografiskTilknyttetKontorEntity[fnr.value]
                gtKontorEtterEndring.kontorId shouldBe nyKontorId

                val aoKontorEtterEndirng = ArbeidsOppfolgingKontorEntity[fnr.value]
                aoKontorEtterEndirng.kontorId shouldBe nyKontorId
            }
        }
    }

    @Test
    fun `skal ikke sette ao-kontor men gt-kontor ved addressebeskyttelse endret hvis det er nytt kontor`() = testApplication {
        val fnr = Fnr("12345678940")
        val gammelKontorId = "1234"
        val nyKontorId = "5678"
        application {
            flywayMigrationInTest()
            gittNåværendeAOKontor(fnr, KontorId(gammelKontorId))
            gittNåværendeGtKontor(fnr, KontorId(gammelKontorId))
            val automatiskKontorRutingService = gittRutingServiceMedGtKontor(KontorId(nyKontorId))
            val leesahConsumer = LeesahConsumer(automatiskKontorRutingService, { FnrFunnet(fnr) })

            leesahConsumer.handterLeesahHendelse(AdressebeskyttelseEndret(fnr, Gradering.UGRADERT))

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
        val fnr = Fnr("40445678901")
        val automatiskKontorRutingService = defaultAutomatiskKontorRutingService(
            { a, b, c -> KontorForGtNrFeil("Noe gikk galt") }
        )
        val leesahConsumer = LeesahConsumer(automatiskKontorRutingService, { FnrFunnet(fnr) })

        val resultat = leesahConsumer.handterLeesahHendelse(BostedsadresseEndret(fnr))

        resultat.shouldBeTypeOf<Retry<String, String>>()
        resultat.reason shouldBe "Kunne ikke håndtere endring i bostedsadresse pga feil ved henting av gt-kontor: Noe gikk galt"
    }

    @Test
    fun `skal håndtere at gt-provider kaster throwable`() = testApplication {
        val fnr = Fnr("40445678901")
        val automatiskKontorRutingService = defaultAutomatiskKontorRutingService(
            { a, b, c -> throw Throwable("Noe gikk galt") }
        )
        val leesahConsumer = LeesahConsumer(automatiskKontorRutingService, { FnrFunnet(fnr) })

        val resultat = leesahConsumer.handterLeesahHendelse(BostedsadresseEndret(fnr))

        resultat.shouldBeTypeOf<Retry<String, String>>()
        resultat.reason shouldBe "Uventet feil ved håndtering av endring i bostedsadresse: Noe gikk galt"
    }

    private fun defaultAutomatiskKontorRutingService(
        gtProvider: suspend (ident: Ident, strengtFortroligAdresse: HarStrengtFortroligAdresse, skjermet: HarSkjerming) -> KontorForGtNrResultat
    ): AutomatiskKontorRutingService {
        return AutomatiskKontorRutingService(
            KontorTilordningService::tilordneKontor,
            fnrProvider = { throw Throwable("Denne skal ikke brukes") },
            gtKontorProvider = gtProvider,
            aldersProvider = { throw Throwable("Denne skal ikke brukes") },
            profileringProvider = { throw Throwable("Denne skal ikke brukes") },
            erSkjermetProvider = { SkjermingFunnet(HarSkjerming(false)) },
            harStrengtFortroligAdresseProvider = { HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false)) },
            isUnderOppfolgingProvider = { AktivOppfolgingsperiode(Fnr("66666666666"), OppfolgingsperiodeId(UUID.randomUUID()), OffsetDateTime.now()) }
        )
    }

    private fun gittRutingServiceMedGtKontor(kontorId: KontorId): AutomatiskKontorRutingService {
        return defaultAutomatiskKontorRutingService(
            { a, b, c -> KontorForGtNrFantDefaultKontor(kontorId, c, b, GeografiskTilknytningBydelNr("3131")) }
        )
    }

    private fun gittNåværendeGtKontor(fnr: Fnr, kontorId: KontorId) {
        transaction {
            GeografiskTilknytningKontorTable.insert {
                it[id] = fnr.value
                it[this.kontorId] = kontorId.id
                it[this.createdAt] = ZonedDateTime.now().toOffsetDateTime()
                it[this.updatedAt] = ZonedDateTime.now().toOffsetDateTime()
            }
        }
    }

    private fun gittNåværendeAOKontor(fnr: Fnr, kontorId: KontorId) {
        transaction {
            ArbeidsOppfolgingKontorTable.insert {
                it[id] = fnr.value
                it[this.kontorId] = kontorId.id
                it[endretAv] = "test"
                it[endretAvType] = "VEILEDER"
                it[createdAt] = ZonedDateTime.now().toOffsetDateTime()
                it[updatedAt] = ZonedDateTime.now().toOffsetDateTime()
            }
        }
    }
}