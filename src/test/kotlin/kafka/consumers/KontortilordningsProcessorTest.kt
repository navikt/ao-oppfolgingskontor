package kafka.consumers;

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import no.nav.db.Fnr
import no.nav.db.Ident
import no.nav.domain.HarSkjerming
import no.nav.domain.HarStrengtFortroligAdresse
import no.nav.domain.KontorId
import no.nav.domain.OppfolgingsperiodeId
import no.nav.domain.externalEvents.OppfolgingsperiodeStartet
import no.nav.http.client.AlderFunnet
import no.nav.http.client.AlderIkkeFunnet
import no.nav.http.client.GeografiskTilknytningKommuneNr
import no.nav.http.client.HarStrengtFortroligAdresseFunnet
import no.nav.http.client.SkjermingFunnet
import no.nav.http.client.arbeidssogerregisteret.ProfileringFunnet
import no.nav.http.client.arbeidssogerregisteret.ProfileringsResultat
import no.nav.kafka.consumers.KontortilordningsProcessor
import no.nav.kafka.processor.Retry
import no.nav.kafka.processor.Skip
import no.nav.services.AktivOppfolgingsperiode
import no.nav.services.AutomatiskKontorRutingService
import domain.kontorForGt.KontorForGtFantDefaultKontor
import no.nav.domain.externalEvents.OppfolgingsperiodeEndret
import org.apache.kafka.streams.processor.api.Record
import org.junit.jupiter.api.Test
import utils.Outcome
import java.time.Instant
import java.time.ZonedDateTime
import java.util.UUID

class KontortilordningsProcessorTest {  

    @Test
    fun `skal returnere Retry på uventete feil `() {
        val oppfolgingsperiode = oppfolgingsperiodeStartet()
        val processor = KontortilordningsProcessor(
            hardtFeilendeAutomatiskKontorRutingService(),
            false,
            false
        )
        val record: Record<Ident, OppfolgingsperiodeEndret> = Record(
            oppfolgingsperiode.fnr as Ident,
            oppfolgingsperiode,
            Instant.now().toEpochMilli(),
        )

        val result = processor.process(record)

        result.shouldBeInstanceOf<Retry<*, *>>()
        result.reason shouldBe "Klarte ikke behandle oppfolgingsperiode melding: Feilet"
    }

    @Test
    fun `skal ikke hoppe over melding hvis ikke konfigurert slik`() {
        val oppfolgingsperiode = oppfolgingsperiodeStartet()
        val processor = KontortilordningsProcessor(
            feilendeAutomatiskKontorRutingService(oppfolgingsperiode),
            false,
            false
        )
        val record: Record<Ident, OppfolgingsperiodeEndret> = Record(
            oppfolgingsperiode.fnr as Ident,
            oppfolgingsperiode,
            Instant.now().toEpochMilli(),
        )

        val result = processor.process(record)

        result.shouldBeInstanceOf<Retry<*, *>>()
    }

    @Test
    fun `skal hoppe over melding hvis Retry på uventete feil `() {
        val oppfolgingsperiode = oppfolgingsperiodeStartet()
        val processor = KontortilordningsProcessor(
            feilendeAutomatiskKontorRutingService(oppfolgingsperiode),
            true,
            false
        )
        val record: Record<Ident, OppfolgingsperiodeEndret> = Record(
            oppfolgingsperiode.fnr as Ident,
            oppfolgingsperiode,
            Instant.now().toEpochMilli(),
        )

        val result = processor.process(record)

        result.shouldBeInstanceOf<Skip<*, *>>()
    }

    fun oppfolgingsperiodeStartet(): OppfolgingsperiodeStartet {
        return OppfolgingsperiodeStartet(
            Fnr("12211221333", Ident.HistoriskStatus.AKTIV),
            ZonedDateTime.now(),
            OppfolgingsperiodeId(UUID.randomUUID()),
            true
        )
    }

    fun automatiskKontorRutingService(oppfolging: OppfolgingsperiodeStartet): AutomatiskKontorRutingService {
        return AutomatiskKontorRutingService(
            { a, b, c -> KontorForGtFantDefaultKontor(KontorId("3131"), HarSkjerming(false),
                HarStrengtFortroligAdresse(false), GeografiskTilknytningKommuneNr("3131")) },
            { AlderFunnet(33) },
            { ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER) },
            { SkjermingFunnet(HarSkjerming(false)) },
            { HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false)) },
            { AktivOppfolgingsperiode(
                oppfolging.fnr,
                oppfolging.periodeId,
                oppfolging.startDato.toOffsetDateTime()
            ) },
            { _, _ -> Outcome.Success(false)  }
        )
    }

    fun feilendeAutomatiskKontorRutingService(oppfolging: OppfolgingsperiodeStartet): AutomatiskKontorRutingService {
        return AutomatiskKontorRutingService(
            { a, b, c -> KontorForGtFantDefaultKontor(KontorId("3131"), HarSkjerming(false),
                HarStrengtFortroligAdresse(false), GeografiskTilknytningKommuneNr("3131")) },
            { AlderIkkeFunnet("Ingen foedselsdato i felt 'foedselsdato' fra pdl-spørring, dårlig data i dev?") },
            { ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER) },
            { SkjermingFunnet(HarSkjerming(false)) },
            { HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false)) },
            { AktivOppfolgingsperiode(
                oppfolging.fnr,
                oppfolging.periodeId,
                oppfolging.startDato.toOffsetDateTime()
            ) },
            { _, _ -> Outcome.Success(false)  }
        )
    }

    fun hardtFeilendeAutomatiskKontorRutingService(): AutomatiskKontorRutingService {
        return AutomatiskKontorRutingService(
            { a, b, c -> throw NotImplementedError("Feilet") },
            { throw NotImplementedError("Feilet") },
            { throw NotImplementedError("Feilet") },
            { throw NotImplementedError("Feilet") },
            { throw NotImplementedError("Feilet") },
            {  throw NotImplementedError("Feilet") },
            { _, _ -> Outcome.Success(false)  }
        )
    }

}
