package no.nav.domain.events

import no.nav.domain.KontorEndringsType
import no.nav.domain.KontorHistorikkInnslag
import no.nav.domain.KontorTilordning
import no.nav.domain.KontorType
import no.nav.domain.Registrant
import org.slf4j.LoggerFactory

class AOKontorEndretPgaNorskGT(
    kontorTilordning: KontorTilordning,
    registrant: Registrant,
) : AOKontorEndret(kontorTilordning, registrant) {
    val log = LoggerFactory.getLogger(this::class.java)

    override fun kontorEndringsType(): KontorEndringsType =
        KontorEndringsType.FikkNorskGt

    override fun toHistorikkInnslag(): KontorHistorikkInnslag {
        return KontorHistorikkInnslag(
            kontorId = this.tilordning.kontorId,
            ident = this.tilordning.fnr,
            registrant = registrant,
            kontorendringstype = this.kontorEndringsType(),
            kontorType = KontorType.ARBEIDSOPPFOLGING,
            oppfolgingId = this.tilordning.oppfolgingsperiodeId
        )
    }

    override fun equals(other: Any?): Boolean {
        if (other !is AOKontorEndretPgaNorskGT) return false
        return this.tilordning == other.tilordning
    }

    override fun logg() {
        log.info("AO kontor endret pga person tildelt Nav IT fikk norsk GT")
    }
}