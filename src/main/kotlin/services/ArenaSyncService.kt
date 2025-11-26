package services

import http.client.ArenakontorFunnet
import http.client.ArenakontorIkkeFunnet
import http.client.ArenakontorOppslagFeilet
import http.client.VeilarbArenaClient
import no.nav.AOPrincipal
import no.nav.db.IdentSomKanLagres
import no.nav.domain.KontorTilordning
import no.nav.domain.events.EndringPaaOppfolgingsBrukerFraArena
import no.nav.services.AktivOppfolgingsperiode
import no.nav.services.KontorTilhorighetService
import no.nav.services.KontorTilordningService
import no.nav.services.NotUnderOppfolging
import no.nav.services.OppfolgingperiodeOppslagFeil

class ArenaSyncService(
    val veilarbArenaClient: VeilarbArenaClient,
    val kontorTilordningService: KontorTilordningService,
    val kontorTilhorighetService: KontorTilhorighetService,
    val oppfolgingsperiodeService: OppfolgingsperiodeService,
) {

    suspend fun refreshArenaKontor(identer: List<IdentSomKanLagres>) {
        identer.map { refreshArenaKontor(it) }
    }

    suspend fun refreshArenaKontor(ident: IdentSomKanLagres) {
        val currentOpenOppfolgingsperiode = when (val result = oppfolgingsperiodeService.getCurrentOppfolgingsperiode(ident)) {
            is AktivOppfolgingsperiode -> result
            NotUnderOppfolging -> throw Exception("Kan ikke sette arenakontor på brukere som ikke er under oppfølging")
            is OppfolgingperiodeOppslagFeil -> throw Exception("Noe gikk galt ved henting av oppfølgingsperioden til bruker: ${result.message}")
        }

        val currentLocalArenaKontor = kontorTilhorighetService.getArenaKontorMedOppfolgingsperiode(ident)
        val currentRemoteArenaKontor = when (val result = veilarbArenaClient.hentArenaKontor(ident)) {
            is ArenakontorFunnet -> result
            is ArenakontorIkkeFunnet -> null
            is ArenakontorOppslagFeilet -> throw Exception("Arena kontor oppslag feilet")
        }

        if (currentLocalArenaKontor == null) throw Exception("Støtter ikke å refreshe arena kontor på brukere som ikke har arenakontor")

        if (currentRemoteArenaKontor != null && currentRemoteArenaKontor.kontorId != currentLocalArenaKontor.kontorId) {
            kontorTilordningService.tilordneKontor(
                EndringPaaOppfolgingsBrukerFraArena(
                    kontorTilordning = KontorTilordning(
                        ident,
                        currentRemoteArenaKontor.kontorId,
                        currentOpenOppfolgingsperiode.periodeId
                    ),
                    sistEndretIArena = currentRemoteArenaKontor.sistEndret.toOffsetDateTime()
                )
            )
        }
    }
}