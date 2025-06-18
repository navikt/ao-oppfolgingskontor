package no.nav.domain.events

import no.nav.db.Fnr
import no.nav.domain.KontorEndringsType
import no.nav.domain.KontorHIstorikkInnslag
import no.nav.domain.KontorId
import no.nav.domain.KontorTilordning
import no.nav.domain.System
import no.nav.http.logger

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

class OppfolgingsperiodeStartetNoeTilordning(
    fnr: Fnr,
): AOKontorEndret(KontorTilordning(fnr, KontorId("4154")), System()) {
    private val rutingResultat: RutingResultat = RutingResultat.RutetTilNOE
    override fun toHistorikkInnslag(): KontorHIstorikkInnslag {
        return KontorHIstorikkInnslag(
            kontorId = tilordning.kontorId,
            fnr = tilordning.fnr,
            registrant = registrant,
            kontorendringstype = rutingResultat.toKontorEndringsType(),
        )
    }

    override fun logg() {
        logger.info("bruker ble rutet til NOE")
    }
}

class OppfolgingsPeriodeStartetLokalKontorTilordning(
    kontorTilordning: KontorTilordning,
): AOKontorEndret(kontorTilordning, System()) {
    val rutingResultat: RutingResultat = RutingResultat.RutetTilLokalkontor
    override fun toHistorikkInnslag(): KontorHIstorikkInnslag {
        return KontorHIstorikkInnslag(
            kontorId = tilordning.kontorId,
            fnr = tilordning.fnr,
            registrant = registrant,
            kontorendringstype = rutingResultat.toKontorEndringsType(),
        )
    }

    override fun logg() {
        logger.info(
            "OppfolgingsPeriodeStartetTilordning: kontorId=${tilordning.kontorId}, rutingResultat=$rutingResultat, registrant=${registrant.getType()}"
        )
    }
}