package services

import domain.IdenterFunnet
import domain.IdenterIkkeFunnet
import domain.IdenterOppslagFeil
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.util.reflect.instanceOf
import no.nav.domain.OppfolgingsperiodeId
import no.nav.domain.externalEvents.OppfolgingsperiodeStartet
import no.nav.http.client.IdentFunnet
import no.nav.http.client.IdentIkkeFunnet
import no.nav.http.client.IdentOppslagFeil
import no.nav.services.AktivOppfolgingsperiode
import no.nav.services.OppfolgingperiodeOppslagFeil
import no.nav.services.OppfolgingsperiodeDao
import no.nav.utils.flywayMigrationInTest
import no.nav.utils.gittBrukerUnderOppfolging
import no.nav.utils.kontorTilordningService
import no.nav.utils.randomDnr
import no.nav.utils.randomFnr
import no.nav.utils.randomInternIdent
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.time.ZonedDateTime
import java.util.UUID

class OppfolgingsperiodeServiceTest {

    companion object {
        @BeforeAll
        @JvmStatic
        fun setup() {
            flywayMigrationInTest()
        }
    }

    @Test
    fun `getCurrentOppfolgingsperiode - feilhåndtering - input er IdentIkkeFunnet`() {
        val oppfolgingsperiodeService = OppfolgingsperiodeService(
            { IdenterOppslagFeil("Noe gikk galt") },
            kontorTilordningService::slettArbeidsoppfølgingskontorTilordning
        )
        val fnrFunnet = IdentIkkeFunnet("Fant ikke ident")

        oppfolgingsperiodeService.getCurrentOppfolgingsperiode(fnrFunnet).shouldBeInstanceOf<OppfolgingperiodeOppslagFeil>()
    }

    @Test
    fun `getCurrentOppfolgingsperiode - feilhåndtering - input er IdentOppslagFeil`() {
        val oppfolgingsperiodeService = OppfolgingsperiodeService(
            {IdenterOppslagFeil("Noe gikk galt")},
            kontorTilordningService::slettArbeidsoppfølgingskontorTilordning
        )
        val fnrFunnet = IdentOppslagFeil("Feil i oppslag på ident")

        oppfolgingsperiodeService.getCurrentOppfolgingsperiode(fnrFunnet).shouldBeInstanceOf<OppfolgingperiodeOppslagFeil>()
    }

    @Test
    fun `getCurrentOppfolgingsperiode - feilhåndtering hentAlleIdenter feiler med IdenterIkkeFunnet`() {
        val oppslagFeil = IdenterIkkeFunnet("Fant ikke ident")
        val oppfolgingsperiodeService = OppfolgingsperiodeService({ oppslagFeil }, kontorTilordningService::slettArbeidsoppfølgingskontorTilordning)
        val ident = IdentFunnet(randomFnr())

        oppfolgingsperiodeService.getCurrentOppfolgingsperiode(ident).shouldBeInstanceOf<OppfolgingperiodeOppslagFeil>()
    }

    @Test
    fun `getCurrentOppfolgingsperiode - feilhåndtering hentAlleIdenter feiler med IdenterOppslagFeil`() {
        val oppslagFeil = IdenterOppslagFeil("Feil i oppslag på ident")
        val oppfolgingsperiodeService = OppfolgingsperiodeService({ oppslagFeil }, kontorTilordningService::slettArbeidsoppfølgingskontorTilordning)
        val ident = IdentFunnet(randomFnr())

        oppfolgingsperiodeService.getCurrentOppfolgingsperiode(ident).shouldBeInstanceOf<OppfolgingperiodeOppslagFeil>()
    }

    @Test
    fun `handterPeriodeStartet - skal avslutte gamle perioder ved start av ny hvis det finnes flere perioder`() {
        val dnr = randomDnr()
        val fnr = randomFnr()
        val identer = IdenterFunnet(
            listOf(fnr, dnr),
            inputIdent = fnr,
            internIdent = randomInternIdent(),
        )
        val periodeIdGammel = OppfolgingsperiodeId(UUID.randomUUID())
        val periodeIdNy = OppfolgingsperiodeId(UUID.randomUUID())
        val oppfolgingsperiodeService = OppfolgingsperiodeService(
            { identer },
            kontorTilordningService::slettArbeidsoppfølgingskontorTilordning
        )

        gittBrukerUnderOppfolging(
            dnr,
            periodeIdGammel
        )

        oppfolgingsperiodeService
            .handterPeriodeStartet(OppfolgingsperiodeStartet(
                fnr = fnr,
                startDato = ZonedDateTime.now(),
                periodeId = periodeIdNy,
                erArbeidssøkerRegistrering = false,
                foretrukketArbeidsoppfolgingskontor = null,
                kontorOverstyring = null,
            ))
            .shouldBeInstanceOf<OppfølgingsperiodeLagret>()

        transaction {
            val faktiskPeriode = OppfolgingsperiodeDao.getCurrentOppfolgingsperiode(identer)
                .shouldBeInstanceOf<AktivOppfolgingsperiode>()
            faktiskPeriode.periodeId shouldBe periodeIdNy
        }
    }


    @Test
    fun `handterPeriodeStartet - skal avslutte gamle perioder ved start av ny hvis det finnes mer enn en gamle aktive perioder`() {
        val dnr = randomDnr()
        val dnr2 = randomDnr()
        val fnr = randomFnr()
        val identer = IdenterFunnet(
            listOf(fnr, dnr, dnr2),
            inputIdent = fnr,
            internIdent = randomInternIdent(),
        )
        val periodeIdGammel = OppfolgingsperiodeId(UUID.randomUUID())
        val periodeIdGammel2 = OppfolgingsperiodeId(UUID.randomUUID())
        val periodeIdNy = OppfolgingsperiodeId(UUID.randomUUID())
        val oppfolgingsperiodeService = OppfolgingsperiodeService(
            { identer },
            kontorTilordningService::slettArbeidsoppfølgingskontorTilordning
        )

        gittBrukerUnderOppfolging(
            dnr,
            periodeIdGammel
        )
        gittBrukerUnderOppfolging(
            dnr2,
            periodeIdGammel2
        )

        oppfolgingsperiodeService
            .handterPeriodeStartet(OppfolgingsperiodeStartet(
                fnr = fnr,
                startDato = ZonedDateTime.now(),
                periodeId = periodeIdNy,
                erArbeidssøkerRegistrering = false,
                foretrukketArbeidsoppfolgingskontor = null,
                kontorOverstyring = null,
            ))
            .shouldBeInstanceOf<OppfølgingsperiodeLagret>()

        transaction {
            val faktiskPeriode = OppfolgingsperiodeDao.getCurrentOppfolgingsperiode(identer)
                .shouldBeInstanceOf<AktivOppfolgingsperiode>()
            faktiskPeriode.periodeId shouldBe periodeIdNy
        }
    }

}