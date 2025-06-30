package no.nav.domain.events

import no.nav.domain.KontorEndringsType
import no.nav.domain.KontorHistorikkInnslag
import no.nav.domain.KontorTilordning
import no.nav.domain.KontorType
import no.nav.domain.System
import no.nav.http.logger
import java.time.OffsetDateTime

class EndringPaaOppfolgingsBrukerFraArena(
    tilordning: KontorTilordning,
    sistEndretDatoArena: OffsetDateTime,
    offset: Long,
    partition: Int
): ArenaKontorEndret(
    tilordning = tilordning,
    sistEndretDatoArena = sistEndretDatoArena,
    offset = offset,
    partition = partition
) {
    override fun toHistorikkInnslag(): KontorHistorikkInnslag {
        val registrant = System()
        return KontorHistorikkInnslag(
            kontorId = tilordning.kontorId,
            fnr = tilordning.fnr,
            registrant = registrant,
            kontorendringstype = KontorEndringsType.EndretIArena,
            kontorType = KontorType.ARENA,
        )
    }

    override fun logg() {
        logger.info("ArenaKontorTilordning: kontorId=${tilordning.kontorId}, offset=$offset, partition=$partition")
    }
}