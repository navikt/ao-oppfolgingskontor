package no.nav.domain.events

import no.nav.domain.KontorEndringsType
import no.nav.domain.KontorHistorikkInnslag
import no.nav.domain.KontorTilordning
import no.nav.domain.KontorType
import no.nav.domain.System
import org.slf4j.LoggerFactory

class AOKontorEndretPgaAdressebeskyttelseEndret(tilordning: KontorTilordning): AOKontorEndret(tilordning, System()) {
    val logger = LoggerFactory.getLogger(this::class.java)

    override fun toHistorikkInnslag(): KontorHistorikkInnslag {
        return KontorHistorikkInnslag(
            kontorId = tilordning.kontorId,
            fnr = tilordning.fnr,
            registrant = System(),
            kontorendringstype = KontorEndringsType.FikkAddressebeskyttelse,
            kontorType = KontorType.ARBEIDSOPPFOLGING,
            oppfolgingId = tilordning.oppfolgingsperiodeId
        )
    }

    override fun logg() {
        logger.info("AdressebeskyttelseAOEndret: kontorId=${tilordning.kontorId}")
    }

    override fun equals(other: Any?): Boolean {
        if (other is AOKontorEndretPgaAdressebeskyttelseEndret) {
            return this.tilordning == other.tilordning
        }
        return false
    }

    override fun toString(): String {
        return "${this.javaClass.simpleName}(tilordning=$tilordning)"
    }
}
