package no.nav.domain.events

import no.nav.domain.KontorEndringsType
import no.nav.domain.KontorHIstorikkInnslag
import no.nav.domain.KontorTilordning
import no.nav.domain.Registrant
import no.nav.domain.System
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime

sealed class KontorEndretEvent(
    val tilordning: KontorTilordning
) {
    abstract fun toHistorikkInnslag(): KontorHIstorikkInnslag
    abstract fun logg(): Unit
}

sealed class GTKontorEndret(tilordning: KontorTilordning) : KontorEndretEvent(tilordning)
sealed class AOKontorEndret(tilordning: KontorTilordning, val registrant: Registrant) : KontorEndretEvent(tilordning)
sealed class ArenaKontorEndret(tilordning: KontorTilordning, val sistEndretDatoArena: OffsetDateTime, val offset: Long, val partition: Int) : KontorEndretEvent(tilordning)
