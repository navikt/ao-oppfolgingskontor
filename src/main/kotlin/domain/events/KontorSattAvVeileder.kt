package no.nav.domain.events

import no.nav.domain.KontorEndringsType
import no.nav.domain.KontorHIstorikkInnslag
import no.nav.domain.KontorTilordning
import no.nav.domain.Registrant

class KontorSattAvVeileder(tilhorighet: KontorTilordning, registrant: Registrant): AOKontorEndret(tilhorighet, registrant) {
    override fun toHistorikkInnslag(): KontorHIstorikkInnslag {
        return KontorHIstorikkInnslag(
            kontorId = tilhorighet.kontorId,
            fnr = tilhorighet.fnr,
            registrant = registrant,
            kontorendringstype = KontorEndringsType.FlyttetAvVeileder,
        )
    }
}
