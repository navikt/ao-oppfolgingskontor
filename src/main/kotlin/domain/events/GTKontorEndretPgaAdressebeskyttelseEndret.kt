package no.nav.domain.events

import no.nav.domain.HarSkjerming
import no.nav.domain.HarStrengtFortroligAdresse
import no.nav.domain.KontorEndringsType
import no.nav.domain.KontorHistorikkInnslag
import no.nav.domain.KontorTilordning
import no.nav.domain.KontorType
import no.nav.domain.Sensitivitet
import no.nav.domain.System
import org.slf4j.LoggerFactory

class GTKontorEndretPgaAdressebeskyttelseEndret(
    tilordning: KontorTilordning
): GTKontorEndret(tilordning) {
    val sensitivitet = Sensitivitet(
        HarSkjerming(false),
        HarStrengtFortroligAdresse(true)
    )
    val logger = LoggerFactory.getLogger(this::class.java)

    override fun toHistorikkInnslag(): KontorHistorikkInnslag {
        return KontorHistorikkInnslag(
            kontorId = tilordning.kontorId,
            fnr = tilordning.fnr,
            registrant = System(),
            kontorendringstype = KontorEndringsType.FikkAddressebeskyttelse,
            kontorType = KontorType.GEOGRAFISK_TILKNYTNING
        )
    }

    override fun logg() {
        logger.info("AdressebeskyttelseGTEndret: kontorId=${tilordning.kontorId}")
    }

    override fun equals(other: Any?): Boolean {
        if (other is GTKontorEndretPgaAdressebeskyttelseEndret) {
            return this.tilordning == other.tilordning
        }
        return false
    }
}