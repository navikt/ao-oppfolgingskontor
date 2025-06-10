package no.nav.domain.events

import no.nav.domain.KontorEndringsType
import no.nav.domain.KontorHIstorikkInnslag
import no.nav.domain.KontorTilordning
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
    override fun toHistorikkInnslag(): KontorHIstorikkInnslag {
        val registrant = System()
        return KontorHIstorikkInnslag(
            kontorId = tilordning.kontorId,
            fnr = tilordning.fnr,
            registrant = registrant,
            kontorendringstype = KontorEndringsType.EndretIArena,
        )
    }

    override fun logg() {
        logger.info("ArenaKontorTilordning: kontorId=${tilordning.kontorId}, offset=$offset, partition=$partition")
    }
}