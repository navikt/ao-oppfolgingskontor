package http.handlers

import io.kotest.matchers.shouldBe
import io.ktor.http.HttpStatusCode
import io.mockk.Called
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import no.nav.AOPrincipal
import no.nav.NavAnsatt
import no.nav.db.IdentSomKanLagres
import no.nav.domain.HarSkjerming
import no.nav.domain.HarStrengtFortroligAdresse
import no.nav.domain.KontorId
import no.nav.domain.KontorNavn
import no.nav.domain.NavIdent
import no.nav.domain.OppfolgingsperiodeId
import no.nav.domain.events.KontorEndretEvent
import no.nav.http.ArbeidsoppfolgingsKontorTilordningDTO
import no.nav.http.Kontor
import no.nav.http.KontorByttetOkResponseDto
import no.nav.http.client.HarStrengtFortroligAdresseFunnet
import no.nav.http.client.HarStrengtFortroligAdresseIkkeFunnet
import no.nav.http.client.HarStrengtFortroligAdresseOppslagFeil
import no.nav.http.client.HarStrengtFortroligAdresseResult
import no.nav.http.client.SkjermingFunnet
import no.nav.http.client.SkjermingIkkeFunnet
import no.nav.http.client.SkjermingResult
import no.nav.http.client.poaoTilgang.HarIkkeTilgangTilBruker
import no.nav.http.client.poaoTilgang.HarTilgangTilBruker
import no.nav.http.client.poaoTilgang.HarTilgangTilKontor
import no.nav.http.client.poaoTilgang.TilgangTilBrukerOppslagFeil
import no.nav.http.client.poaoTilgang.TilgangTilBrukerResult
import no.nav.http.client.poaoTilgang.TilgangTilKontorResult
import no.nav.services.AktivOppfolgingsperiode
import no.nav.services.NotUnderOppfolging
import no.nav.services.OppfolgingperiodeOppslagFeil
import no.nav.services.OppfolgingsperiodeOppslagResult
import no.nav.utils.randomFnr
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID

class SettKontorHandlerTest {

    val navIdent = NavIdent("A112233")
    val navAnsatt = NavAnsatt(navIdent, UUID.randomUUID())
    val fnr = randomFnr()
    val kontortilordning = ArbeidsoppfolgingsKontorTilordningDTO(
        "3144",
        "fordi",
        fnr.value
    )

    @Test
    fun `Skal kunne sette kontor`() = runTest {
        val handler = defaultHandler(fnr)

        handler.settKontor(
            kontortilordning, navAnsatt,
        ) shouldBe SettKontorSuccess(
            KontorByttetOkResponseDto(
                fraKontor = null,
                tilKontor = Kontor(
                    "Kontor navn",
                    "3144"
                )
            )
        )
    }

    @Test
    fun `Skal svare med 409 når bruker ikke er under oppfølging `() = runTest {
        val handler = defaultHandler(fnr, oppfolgingsperiodeResult = NotUnderOppfolging)

        handler.settKontor(
            kontortilordning, navAnsatt,
        ) shouldBe SettKontorFailure(HttpStatusCode.Conflict, "Bruker er ikke under oppfølging")
    }

    @Test
    fun `Skal svare med 409 når bruker er skjermet `() = runTest {
        val handler = defaultHandler(fnr, skjermingResult = SkjermingFunnet(HarSkjerming(true)))

        handler.settKontor(
            kontortilordning, navAnsatt,
        ) shouldBe SettKontorFailure(HttpStatusCode.Conflict, "Kan ikke bytte kontor på skjermet bruker")
    }

    @Test
    fun `Skal svare med 409 når bruker har strengt fortrolig adresse `() = runTest {
        val handler = defaultHandler(fnr, adresseResult = HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(true)))

        handler.settKontor(
            kontortilordning, navAnsatt,
        ) shouldBe SettKontorFailure(HttpStatusCode.Conflict, "Kan ikke bytte kontor på strengt fortrolig bruker")
    }

    @Test
    fun `Skal svare med 403 når veilder ikke har tilgang og forsøker å sette kontor til noe annet enn eget kontor`() = runTest {
        val handler = defaultHandler(fnr, harTilgang = HarIkkeTilgangTilBruker("Fordi"))

        handler.settKontor(
            kontortilordning, navAnsatt,
        ) shouldBe SettKontorFailure(HttpStatusCode.Forbidden, "Du har ikke tilgang til å endre kontor for denne brukeren")
    }

    @Test
    fun `Skal svare med 200 når veilder ikke har tilgang, men forsøker å sette kontor til eget kontor`() = runTest {
            val handler = defaultHandler(fnr, harTilgang = HarIkkeTilgangTilBruker("Fordi"))

        handler.settKontor(
            kontortilordning, navAnsatt,
        ) shouldBe SettKontorFailure(HttpStatusCode.Forbidden, "Du har ikke tilgang til å endre kontor for denne brukeren")
    }

    @Test
    fun `Skal svare med 500 når tilgangsjekk får en teknisk feil`() = runTest {
        val handler = defaultHandler(fnr, harTilgang = TilgangTilBrukerOppslagFeil("Fordi"))

        handler.settKontor(
            kontortilordning, navAnsatt,
        ) shouldBe SettKontorFailure(HttpStatusCode.InternalServerError, "Noe gikk galt under oppslag av tilgang for bruker: Fordi")
    }

    @Test
    fun `Skal svare med 500 når henting av skjerming feiler`() = runTest {
        val handler = defaultHandler(fnr, skjermingResult = SkjermingIkkeFunnet("Fordi"))

        handler.settKontor(
            kontortilordning, navAnsatt,
        ) shouldBe SettKontorFailure(HttpStatusCode.InternalServerError, "Fordi")
    }

    @Test
    fun `Skal svare med 500 når henting av periode feiler`() = runTest {
        val handler = defaultHandler(fnr, oppfolgingsperiodeResult = OppfolgingperiodeOppslagFeil("Fordi"))

        handler.settKontor(
            kontortilordning, navAnsatt,
        ) shouldBe SettKontorFailure(HttpStatusCode.InternalServerError, "Klarte ikke hente oppfølgingsperiode")
    }

    @Test
    fun `Skal svare med 500 når henting av adresssebeskyttelse feiler`() = runTest {
        val handler = defaultHandler(fnr, adresseResult = HarStrengtFortroligAdresseOppslagFeil("Fordi"))

        handler.settKontor(
            kontortilordning, navAnsatt,
        ) shouldBe SettKontorFailure(HttpStatusCode.InternalServerError, "Fordi")
    }

    @Test
    fun `Skal svare med 500 når henting av adresssebeskyttelse retunerer ingenting`() = runTest {
        val handler = defaultHandler(fnr, adresseResult = HarStrengtFortroligAdresseIkkeFunnet("lol"))

        handler.settKontor(
            kontortilordning, navAnsatt,
        ) shouldBe SettKontorFailure(HttpStatusCode.InternalServerError, "lol")
    }

    @Test
    fun `Skal ikke publisere kontorendring hvis lagring av kontor feiler`() = runTest {
        val publiserMockk = mockk<(KontorEndretEvent) -> Result<Unit>>()
        val handler = defaultHandler(fnr,
            tilordneKontor = { a,b -> throw Exception("Noe gikk galt") },
            publiserKontorEndring = publiserMockk
        )

        handler.settKontor(
            kontortilordning, navAnsatt,
        ) shouldBe SettKontorFailure(HttpStatusCode.InternalServerError, "Kunne ikke oppdatere kontor")
        verify { publiserMockk wasNot Called }
    }

    fun defaultHandler(
        ident: IdentSomKanLagres,
        harTilgang: TilgangTilBrukerResult = HarTilgangTilBruker,
        harTilgangTilKontor: TilgangTilKontorResult = HarTilgangTilKontor,
        skjermingResult: SkjermingResult = SkjermingFunnet(HarSkjerming(false)),
        adresseResult: HarStrengtFortroligAdresseResult = HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false)),
        oppfolgingsperiodeResult: OppfolgingsperiodeOppslagResult = AktivOppfolgingsperiode(ident, OppfolgingsperiodeId(UUID.randomUUID()), startDato = OffsetDateTime.now()),
        tilordneKontor: (event: KontorEndretEvent, brukAORuting: Boolean) -> Unit = { a, b -> Unit },
        publiserKontorEndring: (event: KontorEndretEvent) -> Result<Unit> = { a -> Result.success(Unit) },
    ): SettKontorHandler {
        val hentAoKontor = suspend { a: AOPrincipal, i: IdentSomKanLagres -> null }
        val harTilgang = suspend { a: AOPrincipal, b: IdentSomKanLagres, -> harTilgang }
        val harTilgangTilKontor = suspend { a: AOPrincipal, b: KontorId -> harTilgangTilKontor }

        return SettKontorHandler(
            { KontorNavn("Kontor navn") },
            hentAoKontor,
            harTilgang,
            harTilgangTilKontor,
            { oppfolgingsperiodeResult },
            tilordneKontor,
            publiserKontorEndring,
            { skjermingResult },
            { adresseResult },
            true,
        )
    }
}