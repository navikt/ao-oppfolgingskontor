package domain.kontorForGt

import domain.gtForBruker.GtSomKreverFallback
import no.nav.domain.KontorId

/**
 * Noen brukere mangler GT, andre ganger gir ikke GT noen kontor i NORG (http 404)
 * Når det skjer prøver vi arbeidsfordelings tik NORG endepunktet istedet
 */
sealed class KontorForBrukerMedMangelfullGtResultat
data class KontorForBrukerMedMangelfullGtFunnet(val kontorId: KontorId, val gtForBruker: GtSomKreverFallback) :
    KontorForBrukerMedMangelfullGtResultat()

data class KontorForBrukerMedMangelfullGtIkkeFunnet(val gtForBruker: GtSomKreverFallback) :
    KontorForBrukerMedMangelfullGtResultat()

data class KontorForBrukerMedMangelfullGtFeil(val message: String) : KontorForBrukerMedMangelfullGtResultat()
