package http.handlers

import io.kotest.matchers.shouldBe
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import no.nav.AOPrincipal
import no.nav.NavAnsatt
import no.nav.db.IdentSomKanLagres
import no.nav.domain.HarSkjerming
import no.nav.domain.HarStrengtFortroligAdresse
import no.nav.domain.KontorNavn
import no.nav.domain.NavIdent
import no.nav.domain.OppfolgingsperiodeId
import no.nav.http.ArbeidsoppfolgingsKontorTilordningDTO
import no.nav.http.Kontor
import no.nav.http.KontorByttetOkResponseDto
import no.nav.http.client.HarStrengtFortroligAdresseFunnet
import no.nav.http.client.HarStrengtFortroligAdresseResult
import no.nav.http.client.SkjermingFunnet
import no.nav.http.client.SkjermingResult
import no.nav.http.client.poaoTilgang.HarIkkeTilgang
import no.nav.http.client.poaoTilgang.HarTilgang
import no.nav.http.client.poaoTilgang.TilgangResult
import no.nav.services.AktivOppfolgingsperiode
import no.nav.services.NotUnderOppfolging
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
    fun `Skal svare med 403 når subject ikke har tilgang`() = runTest {
        val handler = defaultHandler(fnr, harTilgang = HarIkkeTilgang("Fordi"))

        handler.settKontor(
            kontortilordning, navAnsatt,
        ) shouldBe SettKontorFailure(HttpStatusCode.Forbidden, "Du har ikke tilgang til å endre kontor for denne brukeren")
    }

    fun defaultHandler(
        ident: IdentSomKanLagres,
        harTilgang: TilgangResult = HarTilgang,
        skjermingResult: SkjermingResult = SkjermingFunnet(HarSkjerming(false)),
        adresseResult: HarStrengtFortroligAdresseResult = HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false)),
        oppfolgingsperiodeResult: OppfolgingsperiodeOppslagResult = AktivOppfolgingsperiode(ident, OppfolgingsperiodeId(UUID.randomUUID()), startDato = OffsetDateTime.now())
    ): SettKontorHandler {
        val hentAoKontor = suspend { a: AOPrincipal, i: IdentSomKanLagres -> null }
        val harTilgang = suspend { a: AOPrincipal, b: IdentSomKanLagres, -> harTilgang }
        return SettKontorHandler(
            { KontorNavn("Kontor navn") },
            hentAoKontor,
            harTilgang,
            { oppfolgingsperiodeResult },
            { event, brukAoRuting -> Unit },
            { event -> Result.success(Unit) },
            { skjermingResult },
            { adresseResult },
            true,
        )
    }
}