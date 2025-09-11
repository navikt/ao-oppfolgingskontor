package services

import no.nav.db.Ident
import no.nav.domain.externalEvents.OppfolgingsperiodeAvsluttet
import no.nav.domain.externalEvents.OppfolgingsperiodeStartet
import no.nav.services.AktivOppfolgingsperiode
import no.nav.services.OppfolgingsperiodeDao
import org.slf4j.LoggerFactory
import utils.Outcome

class OppfolgingsperiodeService {
    val log = LoggerFactory.getLogger(OppfolgingsperiodeService::class.java)

    fun handterPeriodeAvsluttet(oppfolgingsperiode: OppfolgingsperiodeAvsluttet): HandterPeriodeAvsluttetResultat {
        val nåværendeOppfolgingsperiode = getNåværendePeriode(oppfolgingsperiode.fnr)
        val nåværendePeriodeBleAvsluttet = when {
            nåværendeOppfolgingsperiode != null -> {
                if (nåværendeOppfolgingsperiode.startDato.isBefore(oppfolgingsperiode.startDato.toOffsetDateTime())) {
                    OppfolgingsperiodeDao.deleteOppfolgingsperiode(nåværendeOppfolgingsperiode.periodeId) > 0
                } else {
                    false
                }
            }
            else -> false
        }
        val innkommendePeriodeBleAvsluttet = OppfolgingsperiodeDao.deleteOppfolgingsperiode(oppfolgingsperiode.periodeId) > 0
        return when {
            nåværendePeriodeBleAvsluttet && innkommendePeriodeBleAvsluttet -> throw Exception("Dette skal aldri skje! Skal ikke være flere perioder på samme person samtidig ${oppfolgingsperiode.periodeId}, ${nåværendeOppfolgingsperiode?.periodeId}")
            nåværendePeriodeBleAvsluttet -> GammelPeriodeAvsluttet
            innkommendePeriodeBleAvsluttet -> InnkommendePeriodeAvsluttet
            else -> IngenPeriodeAvsluttet
        }
    }

    fun handterPeriodeStartet(oppfolgingsperiode: OppfolgingsperiodeStartet): HandterPeriodeStartetResultat {
        if (OppfolgingsperiodeDao.harNyerePeriodePåIdent(oppfolgingsperiode)) {
            log.warn("Hadde nyere periode på ident, hopper over melding")
            return HaddeNyerePeriodePåIdent
        }
        if (OppfolgingsperiodeDao.finnesPeriode(oppfolgingsperiode.periodeId)) {
            return HaddePeriodeAllerede
        }
        val harBruktPeriodeTidligere = OppfolgingsperiodeDao.harBruktPeriodeTidligere(oppfolgingsperiode.fnr, oppfolgingsperiode.periodeId)
        if (harBruktPeriodeTidligere is Outcome.Failure) {
            throw harBruktPeriodeTidligere.exception
        } else if (harBruktPeriodeTidligere is Outcome.Success && harBruktPeriodeTidligere.data) {
            /* Hvis perioden ikke finnes i oppfolgingsperiode tabellen men har blitt brukt tidligere i historikken
            * leser vi sannsynligvis inn en gammel melding som ikke skal behandles */
            return HarSlettetPeriode
        }
        OppfolgingsperiodeDao.saveOppfolgingsperiode(
            oppfolgingsperiode.fnr,
            oppfolgingsperiode.startDato,
            oppfolgingsperiode.periodeId)
        return OppfølgingsperiodeLagret
    }

    private fun getNåværendePeriode(ident: Ident): AktivOppfolgingsperiode? {
        val currentOppfolgingsperiodeResult = OppfolgingsperiodeDao.getCurrentOppfolgingsperiode(ident)
        return when (currentOppfolgingsperiodeResult) {
            is AktivOppfolgingsperiode -> currentOppfolgingsperiodeResult
            else -> null
        }
    }
}

sealed class HandterPeriodeStartetResultat
object HaddeNyerePeriodePåIdent: HandterPeriodeStartetResultat()
object HaddePeriodeAllerede: HandterPeriodeStartetResultat()
object HarSlettetPeriode: HandterPeriodeStartetResultat()
object OppfølgingsperiodeLagret: HandterPeriodeStartetResultat()

sealed class HandterPeriodeAvsluttetResultat
object IngenPeriodeAvsluttet: HandterPeriodeAvsluttetResultat()
object GammelPeriodeAvsluttet: HandterPeriodeAvsluttetResultat()
object InnkommendePeriodeAvsluttet: HandterPeriodeAvsluttetResultat()