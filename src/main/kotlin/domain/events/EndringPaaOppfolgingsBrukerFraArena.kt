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
    tilhorighet = tilordning,
    sistEndretDatoArena = sistEndretDatoArena,
    offset = offset,
    partition = partition
) {
    override fun toHistorikkInnslag(): KontorHIstorikkInnslag {
        val registrant = System()
        return KontorHIstorikkInnslag(
            kontorId = tilhorighet.kontorId,
            fnr = tilhorighet.fnr,
            registrant = registrant,
            kontorendringstype = KontorEndringsType.EndretIArena,
        )
    }

    override fun logg() {
        logger.info("ArenaKontorTilordning: kontorId=${tilhorighet.kontorId}, offset=$offset, partition=$partition")
    }
}