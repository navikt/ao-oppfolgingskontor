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
import no.nav.http.client.GtLandForBrukerFunnet
import no.nav.http.client.GtSomKreverFallback
import no.nav.http.logger
import no.nav.services.KontorForGtFinnesIkke
import no.nav.services.KontorForGtNrFantDefaultKontor
import no.nav.services.KontorForGtNrFantFallbackKontorForManglendeGt
import no.nav.services.KontorForGtNrFantKontor
import no.nav.services.KontorForGtSuccess

enum class RutingResultat {
    RutetTilNOE,
    FallbackIngenGTFunnet,
    FallbackLandGTFunnet,
    RutetViaNorgFallback,
    RutetViaNorg;
    fun toKontorEndringsType(): KontorEndringsType {
        return when (this) {
            RutetTilNOE -> KontorEndringsType.AutomatiskRutetTilNOE
            RutetViaNorg -> KontorEndringsType.AutomatiskNorgRuting
            RutetViaNorgFallback -> KontorEndringsType.AutomatiskNorgRutingFallback
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
    val kontorForGt: KontorForGtNrFantKontor,
): AOKontorEndret(kontorTilordning, System()) {
    val rutingResultat: RutingResultat = when (kontorForGt) {
        is KontorForGtNrFantDefaultKontor -> RutingResultat.RutetViaNorg
        is KontorForGtNrFantFallbackKontorForManglendeGt -> RutingResultat.RutetViaNorgFallback
    }
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
    val sensitivitet: Sensitivitet,
    val gt: KontorForGtFinnesIkke
)
    : AOKontorEndret(KontorTilordning(
    ident,
    INGEN_GT_KONTOR_FALLBACK,
    oppfolgingsperiodeId), System()) {
    val rutingResultat: RutingResultat = when (gt.gtForBruker as GtSomKreverFallback) {
        is GtForBrukerIkkeFunnet -> RutingResultat.FallbackIngenGTFunnet
        is  GtLandForBrukerFunnet -> RutingResultat.FallbackLandGTFunnet
    }
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

    val rutingResultat: RutingResultat = RutingResultat.RutetViaNorg

    constructor(
        kontorTilordning: KontorTilordning,
        gtKontorResultat: KontorForGtNrFantKontor
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