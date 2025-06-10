package no.nav.domain.events

import no.nav.domain.KontorEndringsType
import no.nav.domain.KontorHIstorikkInnslag
import no.nav.domain.KontorTilordning
import no.nav.domain.Registrant
import no.nav.http.logger

class KontorSattAvVeileder(tilhorighet: KontorTilordning, registrant: Registrant): AOKontorEndret(tilhorighet, registrant) {
    override fun toHistorikkInnslag(): KontorHIstorikkInnslag {
        return KontorHIstorikkInnslag(
            kontorId = tilordning.kontorId,
            fnr = tilordning.fnr,
            registrant = registrant,
            kontorendringstype = KontorEndringsType.FlyttetAvVeileder,
        )
    }

    override fun logg() {
        logger.info("KontorSattAvVeileder: kontorId=${tilordning.kontorId}")
    }
}
