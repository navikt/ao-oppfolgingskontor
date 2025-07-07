package no.nav.domain.events

import no.nav.db.Fnr
import no.nav.domain.KontorEndringsType
import no.nav.domain.KontorHistorikkInnslag
import no.nav.domain.KontorId
import no.nav.domain.KontorTilordning
import no.nav.domain.KontorType
import no.nav.domain.Sensitivitet
import no.nav.domain.System
import no.nav.http.logger

enum class RutingResultat {
    RutetTilNOE,
    FallbackIngenGTFunnet,
    RutetTilLokalkontor;
    fun toKontorEndringsType(): KontorEndringsType {
        return when (this) {
            RutetTilNOE -> KontorEndringsType.AutomatiskRutetTilNOE
            RutetTilLokalkontor -> KontorEndringsType.AutomatiskRutetTilLokalkontor
            FallbackIngenGTFunnet -> KontorEndringsType.AutomatiskRutetTilNavItManglerGt
        }
    }
}

data class OppfolgingsperiodeStartetNoeTilordning(
    val fnr: Fnr,
): AOKontorEndret(KontorTilordning(fnr, KontorId("4154")), System()) {
    private val rutingResultat: RutingResultat = RutingResultat.RutetTilNOE
    override fun toHistorikkInnslag(): KontorHistorikkInnslag {
        return KontorHistorikkInnslag(
            kontorId = tilordning.kontorId,
            fnr = tilordning.fnr,
            registrant = registrant,
            kontorendringstype = rutingResultat.toKontorEndringsType(),
            kontorType = KontorType.ARBEIDSOPPFOLGING
        )
    }

    override fun logg() {
        logger.info("bruker ble rutet til NOE")
    }
}

data class OppfolgingsPeriodeStartetLokalKontorTilordning(
    val kontorTilordning: KontorTilordning,
    val sensitivitet: Sensitivitet
): AOKontorEndret(kontorTilordning, System()) {
    val rutingResultat: RutingResultat = RutingResultat.RutetTilLokalkontor
    override fun toHistorikkInnslag(): KontorHistorikkInnslag {
        return KontorHistorikkInnslag(
            kontorId = tilordning.kontorId,
            fnr = tilordning.fnr,
            registrant = registrant,
            kontorendringstype = rutingResultat.toKontorEndringsType(),
            kontorType = KontorType.ARBEIDSOPPFOLGING
        )
    }

    override fun logg() {
        logger.info(
            "OppfolgingsPeriodeStartetTilordning: kontorId=${tilordning.kontorId}, rutingResultat=$rutingResultat, registrant=${registrant.getType()}"
        )
    }
}

data class OppfolgingsPeriodeStartetFallbackKontorTilordning(val fnr: Fnr, val sensitivitet: Sensitivitet) : AOKontorEndret(KontorTilordning(fnr, KontorId("2990")), System()) {
    val rutingResultat: RutingResultat = RutingResultat.RutetTilLokalkontor
    override fun toHistorikkInnslag(): KontorHistorikkInnslag {
        return KontorHistorikkInnslag(
            kontorId = tilordning.kontorId,
            fnr = tilordning.fnr,
            registrant = registrant,
            kontorendringstype = rutingResultat.toKontorEndringsType(),
            kontorType = KontorType.ARBEIDSOPPFOLGING
        )
    }

    override fun logg() {
        logger.info(
            "OppfolgingsPeriodeStartetFallbackKontorTilordning: kontorId=${tilordning.kontorId}, rutingResultat=$rutingResultat, registrant=${registrant.getType()}"
        )
    }

}

data class OppfolgingsPeriodeStartetSensitivKontorTilordning(val fnr: Fnr, val sensitivitet: Sensitivitet): AOKontorEndret(KontorTilordning(fnr, KontorId("2990")), System()) {
    val rutingResultat: RutingResultat = RutingResultat.RutetTilLokalkontor
    override fun toHistorikkInnslag(): KontorHistorikkInnslag {
        return KontorHistorikkInnslag(
            kontorId = tilordning.kontorId,
            fnr = tilordning.fnr,
            registrant = registrant,
            kontorendringstype = rutingResultat.toKontorEndringsType(),
            kontorType = KontorType.ARBEIDSOPPFOLGING
        )
    }

    override fun logg() {
        logger.info(
            "OppfolgingsPeriodeStartetSensitivKontorTilordning: kontorId=${tilordning.kontorId}, rutingResultat=$rutingResultat, registrant=${registrant.getType()}, sensitivitet=$sensitivitet"
        )
    }
}