package no.nav.domain.events

import domain.gtForBruker.GtForBrukerIkkeFunnet
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
import domain.gtForBruker.GtLandForBrukerFunnet
import domain.gtForBruker.GtNummerForBrukerFunnet
import no.nav.http.logger
import domain.kontorForGt.KontorForGtFantIkkeKontor
import domain.kontorForGt.KontorForGtFantDefaultKontor
import domain.kontorForGt.KontorForGtNrFantFallbackKontorForManglendeGt
import domain.kontorForGt.KontorForGtFantKontor
import domain.kontorForGt.KontorForGtFantKontorForArbeidsgiverAdresse
import domain.kontorForGt.KontorForGtSuccess

enum class RutingResultat {
    RutetTilNOE,
    RutetManuelt,
    FallbackIngenGTFunnet,
    FallbackLandGTFunnet,
    FallbackIngenKontorFunnetForGT,
    RutetViaNorgFallback,
    RutetViaArbeidsgiversAdresseFallback,
    RutetViaNorg;

    fun toKontorEndringsType(): KontorEndringsType {
        return when (this) {
            RutetTilNOE -> KontorEndringsType.AutomatiskRutetTilNOE
            RutetManuelt -> KontorEndringsType.StartKontorSattManueltAvVeileder
            RutetViaNorg -> KontorEndringsType.AutomatiskNorgRuting
            RutetViaNorgFallback -> KontorEndringsType.AutomatiskNorgRutingFallback
            FallbackIngenGTFunnet -> KontorEndringsType.AutomatiskRutetTilNavItManglerGt
            FallbackLandGTFunnet -> KontorEndringsType.AutomatiskRutetTilNavItGtErLand
            FallbackIngenKontorFunnetForGT -> KontorEndringsType.AutomatiskRutetTilNavItUgyldigGt
            RutetViaArbeidsgiversAdresseFallback -> KontorEndringsType.AutomatiskRutingArbeidsgiverFallback
        }
    }
}

data class OppfolgingsperiodeStartetNoeTilordning(
    val fnr: IdentSomKanLagres,
    val oppfolgingsperiodeId: OppfolgingsperiodeId,
) : AOKontorEndret(KontorTilordning(fnr, KontorId("4154"), oppfolgingsperiodeId), System()) {
    private val rutingResultat: RutingResultat = RutingResultat.RutetTilNOE
    override fun kontorEndringsType(): KontorEndringsType = rutingResultat.toKontorEndringsType()
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
    val kontorForGt: KontorForGtFantKontor,
) : AOKontorEndret(kontorTilordning, System()) {
    val rutingResultat: RutingResultat = when (kontorForGt) {
        is KontorForGtFantDefaultKontor -> RutingResultat.RutetViaNorg
        is KontorForGtNrFantFallbackKontorForManglendeGt -> RutingResultat.RutetViaNorgFallback
        is KontorForGtFantKontorForArbeidsgiverAdresse -> RutingResultat.RutetViaArbeidsgiversAdresseFallback
    }

    override fun kontorEndringsType(): KontorEndringsType = rutingResultat.toKontorEndringsType()
    override fun toHistorikkInnslag(): KontorHistorikkInnslag {
        return KontorHistorikkInnslag(
            kontorId = tilordning.kontorId,
            ident = tilordning.fnr,
            registrant = registrant,
            kontorendringstype = this.kontorEndringsType(),
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
    val gt: KontorForGtFantIkkeKontor
) : AOKontorEndret(
    KontorTilordning(
        ident,
        INGEN_GT_KONTOR_FALLBACK,
        oppfolgingsperiodeId
    ), System()
) {
    val rutingResultat: RutingResultat = when (gt.gtForBruker) {
        is GtForBrukerIkkeFunnet -> RutingResultat.FallbackIngenGTFunnet
        is GtLandForBrukerFunnet -> RutingResultat.FallbackLandGTFunnet
        // Noen GT-er gir 404 i norg (feks kommunr i kommuner med bydeler)
        is GtNummerForBrukerFunnet -> RutingResultat.FallbackIngenKontorFunnetForGT
    }
    override fun kontorEndringsType(): KontorEndringsType = rutingResultat.toKontorEndringsType()

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
    val gtKontorResultat: KontorForGtSuccess
) : AOKontorEndret(kontorTilordning, System()) {

    val rutingResultat: RutingResultat = RutingResultat.RutetViaNorg
    override fun kontorEndringsType(): KontorEndringsType = rutingResultat.toKontorEndringsType()

    constructor(
        kontorTilordning: KontorTilordning,
        gtKontorResultat: KontorForGtFantKontor
    ) : this(
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


data class OppfolgingsperiodeStartetManuellTilordning(
    val fnr: IdentSomKanLagres,
    val oppfolgingsperiodeId: OppfolgingsperiodeId,
    val kontorId: KontorId,
) : AOKontorEndret(KontorTilordning(fnr, kontorId, oppfolgingsperiodeId), System()) {
    private val rutingResultat: RutingResultat = RutingResultat.RutetManuelt
    override fun kontorEndringsType(): KontorEndringsType = rutingResultat.toKontorEndringsType()
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
        logger.info("brukers kontor ved start oppfolging ble satt manuelt (overstyrt)")
    }
}