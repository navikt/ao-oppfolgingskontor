package no.nav.domain.events

import no.nav.domain.KontorEndringsType
import no.nav.domain.KontorHistorikkInnslag
import no.nav.domain.KontorTilordning
import no.nav.domain.KontorType
import no.nav.domain.Registrant
import no.nav.http.logger

class KontorSattAvVeileder(tilhorighet: KontorTilordning, registrant: Registrant): AOKontorEndret(tilhorighet, registrant) {
    override fun toHistorikkInnslag(): KontorHistorikkInnslag {
        return KontorHistorikkInnslag(
            kontorId = tilordning.kontorId,
            fnr = tilordning.fnr,
            registrant = registrant,
            kontorendringstype = KontorEndringsType.FlyttetAvVeileder,
            kontorType = KontorType.ARBEIDSOPPFOLGING
        )
    }

    override fun logg() {
        logger.info("KontorSattAvVeileder: kontorId=${tilordning.kontorId}")
    }
}
