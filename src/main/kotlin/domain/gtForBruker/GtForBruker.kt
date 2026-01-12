package domain.gtForBruker

import no.nav.http.client.GeografiskTilknytningLand
import no.nav.http.client.GeografiskTilknytningNr

sealed interface GtSomKreverFallback
sealed class GtForBrukerResult
sealed class GtForBrukerSuccess : GtForBrukerResult()
sealed class GtForBrukerFunnet : GtForBrukerSuccess()
data class GtNummerForBrukerFunnet(val gtNr: GeografiskTilknytningNr) : GtForBrukerFunnet() {
    override fun toString() = "${gtNr.value} type: ${gtNr.type.name}"
}
data class GtLandForBrukerFunnet(val land: GeografiskTilknytningLand) : GtForBrukerFunnet(), GtSomKreverFallback {
    override fun toString() = "${land.value} type: Land"
}
data class GtNummerForBrukerFallbackFunnet(val gtNr: GeografiskTilknytningNr): GtForBrukerFunnet()
data class GtForBrukerIkkeFunnet(val message: String) : GtForBrukerSuccess(), GtSomKreverFallback
data class GtForBrukerOppslagFeil(val message: String) : GtForBrukerResult()
