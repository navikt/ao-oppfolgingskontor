package no.nav.domain.events

import no.nav.domain.KontorEndringsType
import no.nav.domain.KontorHistorikkInnslag
import no.nav.domain.KontorTilordning
import no.nav.domain.KontorType
import no.nav.domain.System
import org.slf4j.LoggerFactory

class AOKontorEndretPgaSkjermingEndret(kontorTilordning: KontorTilordning): AOKontorEndret(kontorTilordning, System()) {
    val log = LoggerFactory.getLogger(this::class.java)

    override fun toHistorikkInnslag(): KontorHistorikkInnslag {
        return KontorHistorikkInnslag(
            kontorId = this.tilordning.kontorId,
            fnr = this.tilordning.fnr,
            registrant = System(),
            kontorendringstype = KontorEndringsType.FikkSkjerming,
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