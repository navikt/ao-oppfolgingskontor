package services

import arrow.core.Either
import domain.IdenterFunnet
import domain.IdenterIkkeFunnet
import domain.IdenterOppslagFeil
import domain.IdenterResult
import kotlinx.coroutines.runBlocking
import no.nav.db.Ident
import no.nav.db.IdentSomKanLagres
import no.nav.db.InternIdent
import no.nav.domain.OppfolgingsperiodeId
import no.nav.domain.externalEvents.OppfolgingsperiodeAvsluttet
import no.nav.domain.externalEvents.OppfolgingsperiodeStartet
import no.nav.http.client.IdentFunnet
import no.nav.http.client.IdentIkkeFunnet
import no.nav.http.client.IdentOppslagFeil
import no.nav.http.client.IdentResult
import no.nav.services.AktivOppfolgingsperiode
import no.nav.services.OppfolgingperiodeOppslagFeil
import no.nav.services.OppfolgingsperiodeDao
import no.nav.services.OppfolgingsperiodeOppslagResult
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import utils.Outcome

class OppfolgingsperiodeService(
    val hentAlleIdenter: suspend (Ident) -> IdenterResult,
    val slettArbeidsoppfolgingskontorTilordning: (OppfolgingsperiodeId) -> Either<Throwable, Int>
) {
    val log = LoggerFactory.getLogger(OppfolgingsperiodeService::class.java)

    fun handterPeriodeAvsluttet(oppfolgingsperiode: OppfolgingsperiodeAvsluttet): HandterPeriodeAvsluttetResultat {
        // Bør variabelnavnet være lagretOppfolgingsperiode?
        val aktivOppfolgingsperiode: AktivOppfolgingsperiode? = getNåværendePeriode(oppfolgingsperiode.fnr)

        return when {
            aktivOppfolgingsperiode == null -> {
                // Ikke gjør noe? Skal egentlig ikke være noe å rydde opp
                IngenAktivePerioderÅAvslutte
            }
            aktivOppfolgingsperiode.periodeId != oppfolgingsperiode.periodeId -> {
                val periodenViHarMottattAvsluttetMeldingPaaErEldreEnnAktivPeriode = aktivOppfolgingsperiode.startDato.isAfter(oppfolgingsperiode.startDato.toOffsetDateTime())

                if (periodenViHarMottattAvsluttetMeldingPaaErEldreEnnAktivPeriode) {
                    return PersonHarNyereAktivPeriode
                } else {
                    // Dette skal aldri skje – vi behandlet ikke avslutning på forrige perioden og mottok ikke startmelding på nåværende periode
                    // Vi må uansett rydde opp perioden som vi faktisk har lagret – i denne appen vet vi ingenting om den nyere annet enn at den nå er avsluttet
                    // Tombstone personen
                    slettArbeidsoppfolgingskontorTilordning(aktivOppfolgingsperiode.periodeId).fold(
                        ifLeft = { throw Exception("Uventet feil ved sletting av arbeidsoppfølgingskontor") },
                        ifRight = { log.info("Slettet $it arbeidsoppfølgingskontor fordi oppfølgingsperiode ble avsluttet") }
                    )
                    slettArbeidsoppfolgingskontorTilordning(oppfolgingsperiode.periodeId).fold(
                        ifLeft = { throw Exception("Uventet feil ved sletting av arbeidsoppfølgingskontor") },
                        ifRight = { log.info("Slettet $it arbeidsoppfølgingskontor fordi oppfølgingsperiode ble avsluttet") }
                    )
                    val slettetAktivOppfølgingsperiode = OppfolgingsperiodeDao.deleteOppfolgingsperiode(aktivOppfolgingsperiode.periodeId) == 1
                    val slettetInnkommenOppfølgingperiode = OppfolgingsperiodeDao.deleteOppfolgingsperiode(oppfolgingsperiode.periodeId) == 1
                    log.warn("Fikk inn en avsluttetmelding på nyere periode enn den vi hadde lagret, dette skal ikke skje. Har ryddet opp. Slettet eldste periode: $slettetAktivOppfølgingsperiode, slettet nyeste periode: $slettetInnkommenOppfølgingperiode")

                    AvsluttetAktivPeriode(aktivOppfolgingsperiode.internIdent)
                }
            }
            aktivOppfolgingsperiode.periodeId == oppfolgingsperiode.periodeId -> {
                // Mottok avslutning på aktiv periode (Happy case)
                slettArbeidsoppfolgingskontorTilordning(aktivOppfolgingsperiode.periodeId).fold(
                    ifLeft = { log.error("Uventet feil ved sletting av arbeidsoppfølgingskontor: ${it.message}") },
                    ifRight = { log.info("Slettet arbeidsoppfølgingskontor fordi oppfølgingsperiode ble avsluttet") }
                )
                OppfolgingsperiodeDao.deleteOppfolgingsperiode(aktivOppfolgingsperiode.periodeId)
                AvsluttetAktivPeriode(aktivOppfolgingsperiode.internIdent)
            }
            else -> throw Exception("Skal ikke skje")
        }
    }

    fun handterPeriodeStartet(oppfolgingsperiode: OppfolgingsperiodeStartet): HandterPeriodeStartetResultat {
        if (OppfolgingsperiodeDao.harNyerePeriodePåIdent(oppfolgingsperiode)) {
            log.warn("Hadde nyere periode på ident, hopper over melding")
            return HaddeNyerePeriodePåIdent
        }
        if (OppfolgingsperiodeDao.finnesPeriode(oppfolgingsperiode.periodeId)) {
            val periodeHarFåttTilordning = when (val result = OppfolgingsperiodeDao.finnesAoKontorPåPeriode(oppfolgingsperiode.periodeId)) {
                is Outcome.Failure -> throw Error("Klarte ikke sjekke om oppfolgingsperiode hadde tilordning: ${result.exception.message}", result.exception)
                is Outcome.Success<Boolean> -> result.data
            }
            return when (periodeHarFåttTilordning) {
                true -> HaddePeriodeMedTilordningAllerede
                false -> HaddePeriodeAlleredeMenManglerTilordning
            }
        }
        val harBruktPeriodeTidligere = OppfolgingsperiodeDao.harBruktPeriodeTidligere(oppfolgingsperiode.periodeId)
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

    private fun catchAsOppslagFeil(block: () -> OppfolgingsperiodeOppslagResult): OppfolgingsperiodeOppslagResult {
        try {
            return block()
        }
        catch (e: Exception) {
            log.error("Error checking oppfolgingsperiode status", e)
            return OppfolgingperiodeOppslagFeil("Database error: ${e.message}")
        }
    }

    fun getCurrentOppfolgingsperiode(ident: IdentSomKanLagres): OppfolgingsperiodeOppslagResult {
        return catchAsOppslagFeil {
            transaction {
                // hentAlleIdenter har fallback til PDL og oppdaterer ident-mapping hvis det er kommet nye identer, derfor er dette i en transaksjon
                when (val result = runBlocking { hentAlleIdenter(ident) }) {
                    is IdenterFunnet -> OppfolgingsperiodeDao.getCurrentOppfolgingsperiode(result)
                    is IdenterIkkeFunnet -> OppfolgingperiodeOppslagFeil("Kunne ikke hente nåværende oppfolgingsperiode, klarte ikke hente alle mappede identer: ${result.message}")
                    is IdenterOppslagFeil -> OppfolgingperiodeOppslagFeil("Kunne ikke hente nåværende oppfolgingsperiode, klarte ikke hente alle mappede identer: ${result.message}")
                }
            }
        }
    }
    fun getCurrentOppfolgingsperiode(ident: IdentResult): OppfolgingsperiodeOppslagResult {
        return catchAsOppslagFeil {
            when (ident) {
                is IdentFunnet -> getCurrentOppfolgingsperiode(ident.ident)
                is IdentIkkeFunnet -> OppfolgingperiodeOppslagFeil("Kunne ikke finne oppfølgingsperiode: ${ident.message}")
                is IdentOppslagFeil -> OppfolgingperiodeOppslagFeil("Kunne ikke finne oppfølgingsperiode: ${ident.message}")
            }
        }
    }

    private fun getNåværendePeriode(ident: IdentSomKanLagres): AktivOppfolgingsperiode? {
        val currentOppfolgingsperiodeResult = getCurrentOppfolgingsperiode(IdentFunnet(ident))
        return when (currentOppfolgingsperiodeResult) {
            is AktivOppfolgingsperiode -> currentOppfolgingsperiodeResult
            else -> null
        }
    }
}

sealed class HandterPeriodeStartetResultat
object HaddeNyerePeriodePåIdent: HandterPeriodeStartetResultat()
object HaddePeriodeMedTilordningAllerede: HandterPeriodeStartetResultat()
object HaddePeriodeAlleredeMenManglerTilordning: HandterPeriodeStartetResultat()
object HarSlettetPeriode: HandterPeriodeStartetResultat()
object OppfølgingsperiodeLagret: HandterPeriodeStartetResultat()

sealed class HandterPeriodeAvsluttetResultat
object IngenAktivePerioderÅAvslutte: HandterPeriodeAvsluttetResultat()
object PersonHarNyereAktivPeriode: HandterPeriodeAvsluttetResultat()
class AvsluttetAktivPeriode(val internIdent: InternIdent): HandterPeriodeAvsluttetResultat()
//class InnkommendePeriodeAvsluttet(internIdent: InternIdent): HandterPeriodeAvsluttetResultat()