package no.nav.domain.events

import no.nav.db.IdentSomKanLagres
import no.nav.domain.INGEN_GT_KONTOR_FALLBACK
import no.nav.domain.KontorEndringsType
import no.nav.domain.KontorHistorikkInnslag
import no.nav.domain.KontorId
import no.nav.domain.KontorTilordning
import no.nav.domain.KontorType
import no.nav.domain.OppfolgingsperiodeId
import no.nav.domain.Sensitivitet
import no.nav.domain.System
import no.nav.http.client.GtForBrukerIkkeFunnet
import no.nav.http.logger
import no.nav.services.KontorForGtFantLandEllerKontor
import no.nav.services.KontorForGtFinnesIkke
import no.nav.services.KontorForGtSuccess

enum class RutingResultat {
    RutetTilNOE,
    FallbackIngenGTFunnet,
    FallbackLandGTFunnet,
    RutetTilLokalkontorFallback,
    RutetTilLokalkontor;
    fun toKontorEndringsType(): KontorEndringsType {
        return when (this) {
            RutetTilNOE -> KontorEndringsType.AutomatiskRutetTilNOE
            RutetTilLokalkontor -> KontorEndringsType.AutomatiskRutetTilLokalkontor
            RutetTilLokalkontorFallback -> KontorEndringsType.AutomatiskRutetTilLokalkontorFallback
            FallbackIngenGTFunnet -> KontorEndringsType.AutomatiskRutetTilNavItManglerGt
            FallbackLandGTFunnet -> KontorEndringsType.AutomatiskRutetTilNavItGtErLand
        }
    }
}

data class OppfolgingsperiodeStartetNoeTilordning(
    val fnr: IdentSomKanLagres,
    val oppfolgingsperiodeId: OppfolgingsperiodeId,
): AOKontorEndret(KontorTilordning(fnr, KontorId("4154"), oppfolgingsperiodeId), System()) {
    private val rutingResultat: RutingResultat = RutingResultat.RutetTilNOE
    override fun toHistorikkInnslag(): KontorHistorikkInnslag {
        return KontorHistorikkInnslag(
            kontorId = tilordning.kontorId,
            ident = tilordning.fnr,
            registrant = registrant,
            kontorendringstype = rutingResultat.toKontorEndringsType(),
            kontorType = KontorType.ARBEIDSOPPFOLGING,
            oppfolgingId = tilordning.oppfolgingsperiodeId
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
            ident = tilordning.fnr,
            registrant = registrant,
            kontorendringstype = rutingResultat.toKontorEndringsType(),
            kontorType = KontorType.ARBEIDSOPPFOLGING,
            oppfolgingId = tilordning.oppfolgingsperiodeId
        )
    }

    override fun logg() {
        logger.info(
            "OppfolgingsPeriodeStartetTilordning: kontorId=${tilordning.kontorId}, rutingResultat=$rutingResultat, registrant=${registrant.getType()}"
        )
    }
}

data class OppfolgingsPeriodeStartetFallbackKontorTilordning(
    val ident: IdentSomKanLagres,
    val oppfolgingsperiodeId: OppfolgingsperiodeId,
    val sensitivitet: Sensitivitet)
    : AOKontorEndret(KontorTilordning(
    ident,
    INGEN_GT_KONTOR_FALLBACK,
    oppfolgingsperiodeId), System()) {
    val rutingResultat: RutingResultat = RutingResultat.FallbackIngenGTFunnet
    override fun toHistorikkInnslag(): KontorHistorikkInnslag {
        return KontorHistorikkInnslag(
            kontorId = tilordning.kontorId,
            ident = tilordning.fnr,
            registrant = registrant,
            kontorendringstype = rutingResultat.toKontorEndringsType(),
            kontorType = KontorType.ARBEIDSOPPFOLGING,
            oppfolgingId = tilordning.oppfolgingsperiodeId
        )
    }

    override fun logg() {
        logger.info(
            "OppfolgingsPeriodeStartetFallbackKontorTilordning: kontorId=${tilordning.kontorId}, rutingResultat=$rutingResultat, registrant=${registrant.getType()}"
        )
    }

}

data class OppfolgingsPeriodeStartetSensitivKontorTilordning(
    val kontorTilordning: KontorTilordning,
    val sensitivitet: Sensitivitet,
    val gtKontorResultat: KontorForGtSuccess): AOKontorEndret(kontorTilordning, System()) {

    val rutingResultat: RutingResultat = RutingResultat .RutetTilLokalkontor

    constructor(
        kontorTilordning: KontorTilordning,
        gtKontorResultat: KontorForGtFantLandEllerKontor
    ): this(
        kontorTilordning,
        gtKontorResultat.sensitivitet(),
        gtKontorResultat
    )

    override fun toHistorikkInnslag(): KontorHistorikkInnslag {
        return KontorHistorikkInnslag(
            kontorId = tilordning.kontorId,
            ident = tilordning.fnr,
            registrant = registrant,
            kontorendringstype = rutingResultat.toKontorEndringsType(),
            kontorType = KontorType.ARBEIDSOPPFOLGING,
            oppfolgingId = tilordning.oppfolgingsperiodeId,
        )
    }

    override fun logg() {
        logger.info(
            "OppfolgingsPeriodeStartetSensitivKontorTilordning: kontorId=${tilordning.kontorId}, rutingResultat=$rutingResultat, registrant=${registrant.getType()}, sensitivitet=$sensitivitet"
        )
    }
}