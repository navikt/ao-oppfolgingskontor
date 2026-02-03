import http.handlers.SettKontorHandler
import http.handlers.SettKontorSuccess
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import no.nav.AOPrincipal
import no.nav.NavAnsatt
import no.nav.db.Ident
import no.nav.db.IdentSomKanLagres
import no.nav.domain.HarSkjerming
import no.nav.domain.HarStrengtFortroligAdresse
import no.nav.domain.KontorId
import no.nav.domain.KontorNavn
import no.nav.domain.NavIdent
import no.nav.domain.OppfolgingsperiodeId
import no.nav.domain.Veileder
import no.nav.http.ArbeidsoppfolgingsKontorTilordningDTO
import no.nav.http.Kontor
import no.nav.http.KontorByttetOkResponseDto
import no.nav.http.client.HarStrengtFortroligAdresseFunnet
import no.nav.http.client.SkjermingFunnet
import no.nav.http.client.poaoTilgang.HarTilgang
import no.nav.services.AktivOppfolgingsperiode
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


    fun defaultHandler(ident: IdentSomKanLagres): SettKontorHandler {
        val hentAoKontor = suspend { a: AOPrincipal, i: IdentSomKanLagres -> null }
        val harTilgang = suspend { a: AOPrincipal, i: IdentSomKanLagres -> HarTilgang }
        return SettKontorHandler(
            { KontorNavn("Kontor navn") },
            hentAoKontor,
            harTilgang,
            { AktivOppfolgingsperiode(ident, OppfolgingsperiodeId(UUID.randomUUID()), startDato = OffsetDateTime.now()) },
            { event, brukAoRuting -> Unit },
            { event -> Result.success(Unit) },
            { SkjermingFunnet(HarSkjerming(false)) },
            { HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false)) },
            true,
        )
    }
}