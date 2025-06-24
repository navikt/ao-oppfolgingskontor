package no.nav.domain.events

import no.nav.domain.KontorEndringsType
import no.nav.domain.KontorHIstorikkInnslag
import no.nav.domain.KontorTilordning
import no.nav.domain.System
import org.slf4j.LoggerFactory

class GTKontorPgaAdressebeskyttelseEndret(tilordning: KontorTilordning): GTKontorEndret(tilordning) {
    val logger = LoggerFactory.getLogger(this::class.java)

    override fun toHistorikkInnslag(): KontorHIstorikkInnslag {
        return KontorHIstorikkInnslag(
            kontorId = tilordning.kontorId,
            fnr = tilordning.fnr,
            registrant = System(),
            kontorendringstype = KontorEndringsType.FikkAddressebeskyttelse
        )
    }

    override fun logg() {
        logger.info("AdressebeskyttelseGTEndret: kontorId=${tilordning.kontorId}, fnr=${tilordning.fnr}")
    }
}