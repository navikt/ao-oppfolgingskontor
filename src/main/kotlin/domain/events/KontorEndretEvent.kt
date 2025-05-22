package no.nav.domain.events

import no.nav.domain.KontorHIstorikkInnslag
import no.nav.domain.KontorTilordning
import no.nav.domain.Registrant

sealed class KontorEndretEvent(
    val tilhorighet: KontorTilordning
) {
    abstract fun toHistorikkInnslag(): KontorHIstorikkInnslag
}

sealed class GTKontorEndret(tilhorighet: KontorTilordning) : KontorEndretEvent(tilhorighet)
sealed class AOKontorEndret(tilhorighet: KontorTilordning, val registrant: Registrant) : KontorEndretEvent(tilhorighet)
sealed class ArenaKontorEndret(tilhorighet: KontorTilordning, val offset: Long, val partition: Long) : KontorEndretEvent(tilhorighet)
