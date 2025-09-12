package no.nav.domain.events

import no.nav.domain.KontorEndringsType
import no.nav.domain.KontorHistorikkInnslag
import no.nav.domain.KontorTilordning
import no.nav.domain.KontorType
import no.nav.domain.System
import no.nav.http.logger

class ArenaKontorVedOppfolgingsStart(tilordning: KontorTilordning): ArenaKontorEndret(tilordning, null) {
    override fun toHistorikkInnslag(): KontorHistorikkInnslag {
        val registrant = System()
        return KontorHistorikkInnslag(
            kontorId = tilordning.kontorId,
            ident = tilordning.fnr,
            registrant = registrant,
            kontorendringstype = KontorEndringsType.ArenaKontorVedOppfolgingsStart,
            kontorType = KontorType.ARENA,
            oppfolgingId = tilordning.oppfolgingsperiodeId
        )
    }

    override fun logg() {
        logger.info("ArenaKontorTilordning: kontorId={}, oppfolginsperiode={}", tilordning.kontorId, tilordning.oppfolgingsperiodeId)
    }

    override fun equals(other: Any?): Boolean {
        if (other !is ArenaKontorVedOppfolgingsStart) return false
        if (tilordning.fnr != other.tilordning.fnr) return false
        if (tilordning.oppfolgingsperiodeId != other.tilordning.oppfolgingsperiodeId) return false
        if (tilordning.kontorId != other.tilordning.kontorId) return false
        return true
    }
}