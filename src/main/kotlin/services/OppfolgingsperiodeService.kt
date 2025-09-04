package services

import kafka.consumers.OppfolgingsperiodeDTO
import no.nav.db.Ident
import no.nav.domain.OppfolgingsperiodeId
import no.nav.domain.externalEvents.OppfolgingsperiodeAvsluttet
import no.nav.domain.externalEvents.OppfolgingsperiodeStartet
import no.nav.services.AktivOppfolgingsperiode
import no.nav.services.OppfolgingsperiodeDao
import org.slf4j.LoggerFactory
import java.util.UUID

class OppfolgingsperiodeService {
    val log = LoggerFactory.getLogger(OppfolgingsperiodeService::class.java)

    fun handterPeriodeAvsluttet(oppfolgingsperiode: OppfolgingsperiodeAvsluttet) {
        val currentOppfolgingsperiode = getCurrentPeriode(oppfolgingsperiode.fnr)
        when {
            currentOppfolgingsperiode != null -> {
                if (currentOppfolgingsperiode.startDato.isBefore(oppfolgingsperiode.startDato.toOffsetDateTime())) {
                    OppfolgingsperiodeDao.deleteOppfolgingsperiode(currentOppfolgingsperiode.periodeId)
                }
            }
            else -> {}
        }
        OppfolgingsperiodeDao.deleteOppfolgingsperiode(oppfolgingsperiode.periodeId)
    }

    fun handterPeriodeStartet(oppfolgingsperiode: OppfolgingsperiodeStartet): HandterPeriodeStartetResultat {
        if (OppfolgingsperiodeDao.harNyerePeriodePåIdent(oppfolgingsperiode)) {
            log.warn("Hadde nyere periode på ident, hopper over melding")
            return HaddeNyerePeriodePåIdent
        }
        OppfolgingsperiodeDao.saveOppfolgingsperiode(
            oppfolgingsperiode.fnr,
            oppfolgingsperiode.startDato,
            oppfolgingsperiode.periodeId)
        return OppfølgingsperiodeLagret
    }

    fun behandleOppfolgingsperiodeAvsluttetIdentNotFound(oppfolgingsperiodeDto: OppfolgingsperiodeDTO) {
        val oppfolgingsperiodeId = OppfolgingsperiodeId(UUID.fromString(oppfolgingsperiodeDto.uuid))
        OppfolgingsperiodeDao.deleteOppfolgingsperiode(oppfolgingsperiodeId)
    }

    private fun getCurrentPeriode(ident: Ident): AktivOppfolgingsperiode? {
        val currentOppfolgingsperiodeResult = OppfolgingsperiodeDao.getCurrentOppfolgingsperiode(ident)
        return when (currentOppfolgingsperiodeResult) {
            is AktivOppfolgingsperiode -> currentOppfolgingsperiodeResult
            else -> null
        }
    }
}

sealed class HandterPeriodeStartetResultat
object HaddeNyerePeriodePåIdent: HandterPeriodeStartetResultat()
object OppfølgingsperiodeLagret: HandterPeriodeStartetResultat()