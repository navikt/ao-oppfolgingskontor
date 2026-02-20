package services

import domain.IdenterIkkeFunnet
import domain.IdenterOppslagFeil
import io.kotest.matchers.types.shouldBeInstanceOf
import no.nav.http.client.IdentFunnet
import no.nav.http.client.IdentIkkeFunnet
import no.nav.http.client.IdentOppslagFeil
import no.nav.services.OppfolgingperiodeOppslagFeil
import no.nav.utils.kontorTilordningService
import no.nav.utils.randomFnr
import org.junit.jupiter.api.Test

class OppfolgingsperiodeServiceTest {

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

}