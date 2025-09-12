package no.nav.domain.events

import no.nav.domain.KontorEndringsType
import no.nav.domain.KontorHistorikkInnslag
import no.nav.domain.KontorTilordning
import no.nav.domain.KontorType
import no.nav.domain.Registrant
import no.nav.domain.System
import no.nav.http.logger
import java.time.OffsetDateTime
import java.time.ZonedDateTime

data class ArenaKontorVedOppfolgingsbrukerEndret(
    private val kontortilordning: KontorTilordning,
    private val sistEndretIArena: OffsetDateTime
) : ArenaKontorEndret(kontortilordning, sistEndretIArena) {
    override fun toHistorikkInnslag(): KontorHistorikkInnslag = lagKontorHistorikkInnslag(TODO("LAG TYPE"))
    override fun logg() {
        TODO("Not yet implemented")
    }
}

data class ArenaKontorVedOppfolgingStart(private val kontorTilordning: KontorTilordning) :
    ArenaKontorEndret(kontorTilordning, ZonedDateTime.now().toOffsetDateTime()) {
    override fun toHistorikkInnslag() = lagKontorHistorikkInnslag(KontorEndringsType.ArenaKontorVedOppfolgingsStart)
    override fun logg() {
        logger.info(
            "ArenaKontorTilordning: kontorId={}, oppfolginsperiode={}",
            tilordning.kontorId,
            tilordning.oppfolgingsperiodeId
        )
    }
}

private fun ArenaKontorEndret.lagKontorHistorikkInnslag(kontorEndringsType: KontorEndringsType) =
    KontorHistorikkInnslag(
        kontorId = tilordning.kontorId,
        ident = tilordning.fnr,
        registrant = System(),
        kontorendringstype = KontorEndringsType.ArenaKontorVedOppfolgingsStart,
        kontorType = KontorType.ARENA,
        oppfolgingId = tilordning.oppfolgingsperiodeId
    )
