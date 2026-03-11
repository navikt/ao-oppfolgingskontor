package no.nav.domain.events

import no.nav.domain.HarSkjerming
import no.nav.domain.KontorEndringsType
import no.nav.domain.KontorHistorikkInnslag
import no.nav.domain.KontorTilordning
import no.nav.domain.KontorType
import no.nav.domain.System
import org.slf4j.LoggerFactory

class AOKontorEndretPgaSkjermingEndret(
    kontorTilordning: KontorTilordning,
    private val skjerming: HarSkjerming,
) : AOKontorEndret(kontorTilordning, System()) {
    val log = LoggerFactory.getLogger(this::class.java)

    override fun kontorEndringsType(): KontorEndringsType =
        if (skjerming.value) {
            KontorEndringsType.FikkSkjerming
        } else {
            KontorEndringsType.MistetSkjerming
        }

    override fun toHistorikkInnslag(): KontorHistorikkInnslag {
        return KontorHistorikkInnslag(
            kontorId = this.tilordning.kontorId,
            ident = this.tilordning.fnr,
            registrant = System(),
            kontorendringstype = this.kontorEndringsType(),
            kontorType = KontorType.ARBEIDSOPPFOLGING,
            oppfolgingId = this.tilordning.oppfolgingsperiodeId
        )
    }

    override fun equals(other: Any?): Boolean {
        if (other !is AOKontorEndretPgaSkjermingEndret) return false
        return this.tilordning == other.tilordning
    }

    override fun logg() {
        log.info("AO kontor endret pga person ble skjermet")
    }
}