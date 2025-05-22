package no.nav.domain.events

import no.nav.domain.KontorHIstorikkInnslag
import no.nav.domain.KontorTilordning
import no.nav.domain.Registrant
import java.time.OffsetDateTime

sealed class KontorEndretEvent(
    val tilhorighet: KontorTilordning
) {
    abstract fun toHistorikkInnslag(): KontorHIstorikkInnslag
}

sealed class GTKontorEndret(tilhorighet: KontorTilordning) : KontorEndretEvent(tilhorighet)
sealed class AOKontorEndret(tilhorighet: KontorTilordning, val registrant: Registrant) : KontorEndretEvent(tilhorighet)
sealed class ArenaKontorEndret(tilhorighet: KontorTilordning, val sistEndretDatoArena: OffsetDateTime, val offset: Long, val partition: Int) : KontorEndretEvent(tilhorighet)
