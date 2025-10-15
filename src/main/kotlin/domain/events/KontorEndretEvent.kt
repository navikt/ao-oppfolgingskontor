package no.nav.domain.events

import domain.gtForBruker.GtForBrukerIkkeFunnet
import domain.gtForBruker.GtForBrukerSuccess
import domain.gtForBruker.GtLandForBrukerFunnet
import domain.gtForBruker.GtNummerForBrukerFunnet
import no.nav.domain.HarSkjerming
import no.nav.domain.HarStrengtFortroligAdresse
import no.nav.domain.KontorEndringsType
import no.nav.domain.KontorHistorikkInnslag
import no.nav.domain.KontorTilordning
import no.nav.domain.KontorType
import no.nav.domain.Registrant
import no.nav.domain.System
import no.nav.http.logger
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime

sealed class KontorEndretEvent(
    val tilordning: KontorTilordning
) {
    abstract fun toHistorikkInnslag(): KontorHistorikkInnslag
    abstract fun logg(): Unit

    override fun toString(): String {
        return "${this.javaClass.simpleName}(tilordning=$tilordning)"
    }
}

data class GTKontorEndret(val kontorTilordning: KontorTilordning, val kontorEndringsType: KontorEndringsType, val gt: GtForBrukerSuccess) : KontorEndretEvent(kontorTilordning) {
    val log = LoggerFactory.getLogger(this::class.java)

    fun gt(): String? = when (gt) {
        is GtLandForBrukerFunnet -> gt.land.value
        is GtNummerForBrukerFunnet -> gt.gtNr.value
        is GtForBrukerIkkeFunnet -> null
    }

    fun gtType(): String? = when (gt) {
        is GtLandForBrukerFunnet -> "Land"
        is GtNummerForBrukerFunnet -> gt.gtNr.type.name
        is GtForBrukerIkkeFunnet -> null
    }

    override fun toHistorikkInnslag(): KontorHistorikkInnslag {
        return KontorHistorikkInnslag(
            kontorId = tilordning.kontorId,
            ident = tilordning.fnr,
            registrant = System(),
            kontorendringstype = kontorEndringsType,
            kontorType = KontorType.GEOGRAFISK_TILKNYTNING,
            oppfolgingId = tilordning.oppfolgingsperiodeId
        )
    }

    override fun logg() {
        log.info("GTKontorEndret: kontorId=${tilordning.kontorId}, kontorEndringsType=$kontorEndringsType")
    }

    companion object {
        fun endretPgaAdressebeskyttelseEndret(
            tilordning: KontorTilordning,
            erStrengtFortrolig: HarStrengtFortroligAdresse,
            gt: GtForBrukerSuccess
        ) = GTKontorEndret(
                tilordning,
                if (erStrengtFortrolig.value) KontorEndringsType.FikkAddressebeskyttelse else KontorEndringsType.AddressebeskyttelseMistet,
                gt)

        fun endretPgaSkjermingEndret(
            tilordning: KontorTilordning,
            erSkjermet: HarSkjerming,
            gt: GtForBrukerSuccess) =
            GTKontorEndret(
                tilordning,
                if (erSkjermet.value) KontorEndringsType.FikkSkjerming else KontorEndringsType.MistetSkjerming,
                gt)

        fun endretPgaBostedsadresseEndret(tilordning: KontorTilordning, gt: GtForBrukerSuccess) = GTKontorEndret(
            tilordning,
                KontorEndringsType.EndretBostedsadresse,
                gt)

        fun syncVedStartOppfolging(tilordning: KontorTilordning, gt: GtForBrukerSuccess) = GTKontorEndret(
            tilordning,
            KontorEndringsType.GTKontorVedOppfolgingStart,
            gt)
    }
}
sealed class AOKontorEndret(tilordning: KontorTilordning, val registrant: Registrant) : KontorEndretEvent(tilordning)
sealed class ArenaKontorEndret(tilordning: KontorTilordning, val sistEndretDatoArena: OffsetDateTime) : KontorEndretEvent(tilordning) {
    override fun logg() {
        logger.info("${this::class.simpleName}: kontorId=${tilordning.kontorId}, oppfolginsperiode=${tilordning.oppfolgingsperiodeId}")
    }
}
