package no.nav.domain.events

import no.nav.domain.KontorEndringsType
import no.nav.domain.KontorHistorikkInnslag
import no.nav.domain.KontorTilordning
import no.nav.domain.KontorType
import no.nav.domain.System
import java.time.OffsetDateTime

data class TidligArenaKontorVedOppfolgingStart(
    private val kontortilordning: KontorTilordning,
    private val sistEndretIArena: OffsetDateTime
) : ArenaKontorEndret(kontortilordning, sistEndretIArena) {
    override fun toHistorikkInnslag() = lagKontorHistorikkInnslag(
        KontorEndringsType.TidligArenaKontorVedOppfolgingStart
    )
}

// TODO: Denne skal vel fjernes? Enten henter vi synkront ved oppfølgingStart, ellers er det en ordinær endring
data class ArenaKontorFraOppfolgingsbrukerVedOppfolgingStartMedEtterslep(
    private val kontorTilordning: KontorTilordning,
    private val sistEndretIArena: OffsetDateTime,
): ArenaKontorEndret(
    tilordning = kontorTilordning,
    sistEndretDatoArena = sistEndretIArena
) {
    override fun toHistorikkInnslag() = lagKontorHistorikkInnslag(KontorEndringsType.ArenaKontorVedOppfolgingStartMedEtterslep)
}

data class ArenaKontorHentetSynkrontVedOppfolgingStart(
    private val kontorTilordning: KontorTilordning,
    private val sistEndretIArena: OffsetDateTime,
): ArenaKontorEndret(
    tilordning = kontorTilordning,
    sistEndretDatoArena = sistEndretIArena
) {
    override fun toHistorikkInnslag() = lagKontorHistorikkInnslag(KontorEndringsType.ArenaKontorHentetSynkrontVedOppfolgingsStart)
}

data class EndringPaaOppfolgingsBrukerFraArena(
    private val kontorTilordning: KontorTilordning,
    private val sistEndretIArena: OffsetDateTime,
): ArenaKontorEndret(
    tilordning = kontorTilordning,
    sistEndretDatoArena = sistEndretIArena
) {
    override fun toHistorikkInnslag() = lagKontorHistorikkInnslag(KontorEndringsType.EndretIArena)
}

data class ManuellSynkVeilarbArena(
    private val kontorTilordning: KontorTilordning,
    private val sistEndretIArena: OffsetDateTime,
): ArenaKontorEndret(
    tilordning = kontorTilordning,
    sistEndretDatoArena = sistEndretIArena
) {
    override fun toHistorikkInnslag() = lagKontorHistorikkInnslag(KontorEndringsType.ArenaKontorManuellSynk)
}

private fun ArenaKontorEndret.lagKontorHistorikkInnslag(kontorEndringsType: KontorEndringsType) =
    KontorHistorikkInnslag(
        kontorId = tilordning.kontorId,
        ident = tilordning.fnr,
        registrant = System(),
        kontorendringstype = kontorEndringsType,
        kontorType = KontorType.ARENA,
        oppfolgingId = tilordning.oppfolgingsperiodeId
    )
