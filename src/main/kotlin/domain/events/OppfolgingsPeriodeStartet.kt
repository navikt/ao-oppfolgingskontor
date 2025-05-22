package no.nav.domain.events

import no.nav.domain.KontorEndringsType
import no.nav.domain.KontorHIstorikkInnslag
import no.nav.domain.KontorTilordning
import no.nav.domain.System

enum class RutingResultat {
    RutetTilNOE,
    RutetTilLokalkontor;

    fun toKontorEndringsType(): KontorEndringsType {
        return when (this) {
            RutetTilNOE -> KontorEndringsType.AutomatiskRutetTilNOE
            RutetTilLokalkontor -> KontorEndringsType.AutomatiskRutetTilLokalkontor
        }
    }
}

class OppfolgingsPeriodeStartet(kontorTilhorighet: KontorTilordning, val rutingResultat: RutingResultat): AOKontorEndret(kontorTilhorighet, System()) {
    override fun toHistorikkInnslag(): KontorHIstorikkInnslag {
        return KontorHIstorikkInnslag(
            kontorId = tilhorighet.kontorId,
            fnr = tilhorighet.fnr,
            registrant = registrant,
            kontorendringstype = rutingResultat.toKontorEndringsType(),
        )
    }
}
