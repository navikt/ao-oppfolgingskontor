package no.nav.domain.events

import no.nav.domain.KontorEndringsType
import no.nav.domain.KontorHistorikkInnslag
import no.nav.domain.KontorTilordning
import no.nav.domain.KontorType
import no.nav.domain.System
import org.slf4j.LoggerFactory

class GTKontorEndretPgaBostedsadresseEndret(tilordning: KontorTilordning) : GTKontorEndret(tilordning) {
    val logger = LoggerFactory.getLogger(this::class.java)

    override fun toHistorikkInnslag(): KontorHistorikkInnslag {
        return KontorHistorikkInnslag(
            kontorId = tilordning.kontorId,
            fnr = tilordning.fnr,
            registrant = System(),
            kontorendringstype = KontorEndringsType.EndretBostedsadresse,
            kontorType = KontorType.GEOGRAFISK_TILKNYTNING
        )
    }

    override fun equals(other: Any?): Boolean {
        if(other !is GTKontorEndretPgaBostedsadresseEndret) return false
        return this.tilordning == other.tilordning
    }

    override fun logg() {
        logger.info("BostedsadresseEndret: kontorId=${tilordning.kontorId}")
    }
}
