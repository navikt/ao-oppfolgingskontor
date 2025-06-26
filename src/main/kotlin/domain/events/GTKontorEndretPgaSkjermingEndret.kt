package no.nav.domain.events

import no.nav.domain.KontorEndringsType
import no.nav.domain.KontorHistorikkInnslag
import no.nav.domain.KontorTilordning
import no.nav.domain.KontorType
import no.nav.domain.System
import org.slf4j.LoggerFactory

class GTKontorEndretPgaSkjermingEndret(kontorTilordning: KontorTilordning): GTKontorEndret(kontorTilordning) {
    val log = LoggerFactory.getLogger(this::class.java)

    override fun toHistorikkInnslag(): KontorHistorikkInnslag {
        return KontorHistorikkInnslag(
            kontorId = this.tilordning.kontorId,
            fnr = this.tilordning.fnr,
            registrant = System(),
            kontorendringstype = KontorEndringsType.FikkSkjerming,
            kontorType = KontorType.GEOGRAFISK_TILKNYTNING
        )
    }

    override fun logg() {
        log.info("GT kontor endret pga person ble skjermet")
    }
}