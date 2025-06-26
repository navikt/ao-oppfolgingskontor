package no.nav.services

import no.nav.db.Fnr
import no.nav.http.client.GeografiskTilknytning
import no.nav.http.client.GtForBrukerFunnet
import no.nav.http.client.GtForBrukerResult
import no.nav.http.client.GtForBrukerIkkeFunnet
import no.nav.http.client.poaoTilgang.GTKontorFeil
import no.nav.http.client.poaoTilgang.GTKontorResultat
import org.slf4j.LoggerFactory

class GTNorgService(
    private val gtForBrukerProvider: suspend (fnr: Fnr) -> GtForBrukerResult,
    private val kontorForGtProvider: suspend (gt: GeografiskTilknytning) -> GTKontorResultat,
) {
    val log = LoggerFactory.getLogger(this::class.java)

    suspend fun hentGtKontorForBruker(fnr: Fnr): GTKontorResultat {
        val gtForBruker = gtForBrukerProvider(fnr)
        when (gtForBruker) {
            is GtForBrukerFunnet -> {
                val gt = gtForBruker.gt
                return kontorForGtProvider(gt)
            }
            is GtForBrukerIkkeFunnet -> {
                return GTKontorFeil(gtForBruker.message)
            }
        }
    }

}