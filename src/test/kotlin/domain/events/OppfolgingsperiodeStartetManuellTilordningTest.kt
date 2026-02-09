package domain.events

import io.kotest.matchers.shouldBe
import kafka.consumers.oppfolgingsHendelser.StartetAvType
import no.nav.domain.Bruker
import no.nav.domain.KontorEndringsType
import no.nav.domain.KontorHistorikkInnslag
import no.nav.domain.KontorId
import no.nav.domain.KontorType
import no.nav.domain.NavIdent
import no.nav.domain.OppfolgingsperiodeId
import no.nav.domain.Veileder
import no.nav.domain.events.OppfolgingsperiodeStartetManuellTilordning
import no.nav.domain.externalEvents.KontorOverstyring
import no.nav.utils.randomFnr
import org.junit.jupiter.api.Test
import java.util.UUID

class OppfolgingsperiodeStartetManuellTilordningTest {

    @Test
    fun `Skal oversette manuell overstyring fra veileder til riktig historikkinnslag`() {
        val kontor = KontorId("4411")
        val fnr = randomFnr()
        val veilederIdent = NavIdent("G112233")
        val oppfolgingsperiodeId = OppfolgingsperiodeId(UUID.randomUUID())
        val kontorOverstyring = KontorOverstyring( veilederIdent.id, StartetAvType.VEILEDER,kontor)
        OppfolgingsperiodeStartetManuellTilordning(
            fnr,
            oppfolgingsperiodeId,
            kontorOverstyring,
        ).toHistorikkInnslag() shouldBe KontorHistorikkInnslag(
            kontorId = kontor,
            ident = fnr,
            registrant = Veileder(veilederIdent),
            kontorendringstype = KontorEndringsType.StartKontorSattManueltAvVeileder,
            kontorType = KontorType.ARBEIDSOPPFOLGING,
            oppfolgingId = oppfolgingsperiodeId,
        )
    }

    @Test
    fun `Skal oversette manuell overstyring fra bruker til riktig historikkinnslag`() {
        val kontor = KontorId("4411")
        val fnr = randomFnr()
        val oppfolgingsperiodeId = OppfolgingsperiodeId(UUID.randomUUID())
        val kontorOverstyring = KontorOverstyring( fnr.value, StartetAvType.BRUKER,kontor)
        OppfolgingsperiodeStartetManuellTilordning(
            fnr,
            oppfolgingsperiodeId,
            kontorOverstyring,
        ).toHistorikkInnslag() shouldBe KontorHistorikkInnslag(
            kontorId = kontor,
            ident = fnr,
            registrant = Bruker(fnr),
            kontorendringstype = KontorEndringsType.StartKontorSattManueltAvVeileder,
            kontorType = KontorType.ARBEIDSOPPFOLGING,
            oppfolgingId = oppfolgingsperiodeId,
        )
    }

}