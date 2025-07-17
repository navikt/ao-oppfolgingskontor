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
): ArenaKontorEndret(
    tilordning = tilordning,
    sistEndretDatoArena = sistEndretDatoArena
) {
    override fun toHistorikkInnslag(): KontorHistorikkInnslag {
        val registrant = System()
        return KontorHistorikkInnslag(
            kontorId = tilordning.kontorId,
            ident = tilordning.fnr,
            registrant = registrant,
            kontorendringstype = KontorEndringsType.EndretIArena,
            kontorType = KontorType.ARENA,
            oppfolgingId = tilordning.oppfolgingsperiodeId
        )
    }

    override fun logg() {
        logger.info("ArenaKontorTilordning: kontorId={}, oppfolginsperiode={}", tilordning.kontorId, tilordning.oppfolgingsperiodeId)
    }
}