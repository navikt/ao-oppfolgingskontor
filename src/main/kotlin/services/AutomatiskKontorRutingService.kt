package no.nav.services

import no.nav.db.Fnr
import no.nav.domain.INGEN_GT_KONTOR_FALLBACK
import no.nav.domain.KontorId
import no.nav.domain.KontorTilordning
import no.nav.domain.events.AOKontorEndret
import no.nav.domain.events.AOKontorEndretPgaAdressebeskyttelseEndret
import no.nav.domain.events.AOKontorEndretPgaSkjermingEndret
import no.nav.domain.events.GTKontorEndretPgaAdressebeskyttelseEndret
import no.nav.domain.events.GTKontorEndretPgaBostedsadresseEndret
import no.nav.domain.events.GTKontorEndretPgaSkjermingEndret
import no.nav.domain.events.KontorEndretEvent
import no.nav.domain.events.OppfolgingsPeriodeStartetFallbackKontorTilordning
import no.nav.domain.events.OppfolgingsPeriodeStartetLokalKontorTilordning
import no.nav.domain.events.OppfolgingsperiodeStartetNoeTilordning
import no.nav.domain.externalEvents.AddressebeskyttelseEndret
import no.nav.domain.externalEvents.BostedsadresseEndret
import no.nav.domain.externalEvents.OppfolgingperiodeAvsluttet
import no.nav.domain.externalEvents.OppfolgingsperiodeEndret
import no.nav.domain.externalEvents.SkjermetStatusEndret
import no.nav.domain.externalEvents.erGradert
import no.nav.domain.externalEvents.erStrengtFortrolig
import no.nav.http.client.AlderFunnet
import no.nav.http.client.AlderResult
import no.nav.http.client.FnrFunnet
import no.nav.http.client.FnrIkkeFunnet
import no.nav.http.client.FnrOppslagFeil
import no.nav.http.client.FnrResult
import no.nav.http.client.HarStrengtFortroligAdresseFunnet
import no.nav.http.client.HarStrengtFortroligAdresseIkkeFunnet
import no.nav.http.client.HarStrengtFortroligAdresseOppslagFeil
import no.nav.http.client.HarStrengtFortroligAdresseResult
import no.nav.http.client.SkjermingFunnet
import no.nav.http.client.SkjermingIkkeFunnet
import no.nav.http.client.SkjermingResult
import no.nav.http.client.arbeidssogerregisteret.HentProfileringsResultat
import no.nav.http.client.arbeidssogerregisteret.ProfileringFunnet
import no.nav.http.client.arbeidssogerregisteret.ProfileringsResultat
import no.nav.kafka.consumers.EndringISkjermingResult
import no.nav.kafka.consumers.HåndterPersondataEndretFail
import no.nav.kafka.consumers.HåndterPersondataEndretResultat
import no.nav.kafka.consumers.HåndterPersondataEndretSuccess
import org.slf4j.LoggerFactory

sealed class TilordningResultat
sealed class TilordningSuccess : TilordningResultat()
object TilordningSuccessIngenEndring : TilordningSuccess()
class TilordningSuccessKontorEndret(val kontorEndretEvent: KontorEndretEvent) : TilordningSuccess()
data class TilordningFeil(val message: String) : TilordningResultat()

class AutomatiskKontorRutingService(
    private val tilordneKontor: suspend (kontorEndretEvent: KontorEndretEvent) -> Unit,
    private val gtKontorProvider: suspend (fnr: Fnr, strengtFortroligAdresse: Boolean, skjermet: Boolean) -> GTKontorResultat,
    private val aldersProvider: suspend (fnr: Fnr) -> AlderResult,
    private val fnrProvider: suspend (aktorId: String) -> FnrResult,
    private val profileringProvider: suspend (fnr: Fnr) -> HentProfileringsResultat,
    private val erSkjermetProvider: suspend (fnr: Fnr) -> SkjermingResult,
    private val harStrengtFortroligAdresseProvider: suspend (fnr: Fnr) -> HarStrengtFortroligAdresseResult,
) {
    val log = LoggerFactory.getLogger(this::class.java)

    suspend fun tilordneKontorAutomatisk(oppfolgingsperiodeEndret: OppfolgingsperiodeEndret): TilordningResultat {
        if (oppfolgingsperiodeEndret is OppfolgingperiodeAvsluttet) return TilordningSuccessIngenEndring
        try {
            val fnrResult = fnrProvider(oppfolgingsperiodeEndret.aktorId)
            val fnr = when (fnrResult) {
                is FnrFunnet -> fnrResult.fnr
                is FnrIkkeFunnet -> return TilordningFeil("Fant ikke fnr: ${fnrResult.message}")
                is FnrOppslagFeil -> return TilordningFeil("Feil ved oppslag på fnr: ${fnrResult.message}")
            }
            val erSkjermet = when(val skjermetResult = erSkjermetProvider(fnr)) {
                is SkjermingFunnet -> skjermetResult.skjermet
                is SkjermingIkkeFunnet -> return TilordningFeil("Kunne ikke hente skjerming ved kontortilordning: ${skjermetResult.melding}")
            }
            val harStrengtFortroligAdresse = when (val result = harStrengtFortroligAdresseProvider(fnr)) {
                is HarStrengtFortroligAdresseIkkeFunnet -> return TilordningFeil("Kunne ikke hente adressebeskyttelse ved kontortilordning: ${result.message}")
                is HarStrengtFortroligAdresseOppslagFeil -> return TilordningFeil("Kunne ikke hente adressebeskyttelse ved kontortilordning: ${result.message}")
                is HarStrengtFortroligAdresseFunnet -> result.harStrengtFortroligAdresse
            }

            val gtKontorResultat = gtKontorProvider(fnr, harStrengtFortroligAdresse, erSkjermet)
            if (gtKontorResultat is GTKontorFeil) return TilordningFeil("Feil ved henting av gt-kontor: ${gtKontorResultat.melding}")

            val aldersResultat = aldersProvider(fnr)

            val kontorTilordning = hentTilordning(
                fnr,
                (gtKontorResultat as GTKontorFunnet).kontorId,
                if (aldersResultat is AlderFunnet) aldersResultat.alder else null,
                profileringProvider(fnr),
            )
            tilordneKontor(kontorTilordning)
            return TilordningSuccessKontorEndret(kontorTilordning)
        } catch (e: Exception) {
            log.error("Feil ved tilordning kontor: ${e.message}", e)
            return TilordningFeil("Feil ved tilordning av kontor: ${e.message ?: e.toString()}")
        }
    }

    private fun hentTilordning(
        fnr: String,
        gtKontor: KontorId?,
        alder: Int?,
        profilering: HentProfileringsResultat,
    ): AOKontorEndret {
        if (alder == null) throw IllegalArgumentException("Alder == null")

        log.info("Profilering: $profilering, alder: $alder")

        if (profilering is ProfileringFunnet &&
            profilering.profilering == ProfileringsResultat.ANTATT_GODE_MULIGHETER &&
            alder in 31..59
        ) {
            return OppfolgingsperiodeStartetNoeTilordning(fnr)
        }

        return when {
            gtKontor == null -> OppfolgingsPeriodeStartetFallbackKontorTilordning(fnr)
            else -> OppfolgingsPeriodeStartetLokalKontorTilordning(KontorTilordning(fnr, gtKontor))
        }
    }

    suspend fun handterEndringForBostedsadresse(
        hendelse: BostedsadresseEndret,
    ): HåndterPersondataEndretResultat {
        try {
            val erSkjermet = when(val skjermetResult = erSkjermetProvider(hendelse.fnr)) {
                is SkjermingFunnet -> skjermetResult.skjermet
                is SkjermingIkkeFunnet -> return HåndterPersondataEndretFail(skjermetResult.melding)
            }
            val harStrengtFortroligAdresse = when (val result = harStrengtFortroligAdresseProvider(hendelse.fnr)) {
                is HarStrengtFortroligAdresseIkkeFunnet -> return HåndterPersondataEndretFail("Kunne ikke hente adressebeskyttelse ved endring i bostedsadresse: ${result.message}")
                is HarStrengtFortroligAdresseOppslagFeil -> return HåndterPersondataEndretFail("Kunne ikke hente adressebeskyttelse ved endring i bostedsadresse: ${result.message}")
                is HarStrengtFortroligAdresseFunnet -> result.harStrengtFortroligAdresse
            }

            val gtKontorResultat = gtKontorProvider(hendelse.fnr, harStrengtFortroligAdresse, erSkjermet)
            return when (gtKontorResultat) {
                is GTKontorFunnet -> {
                    KontorTilordningService.tilordneKontor(
                    GTKontorEndretPgaBostedsadresseEndret(
                        KontorTilordning(hendelse.fnr,gtKontorResultat.kontorId)
                    ))
                    HåndterPersondataEndretSuccess
                }
                is GTKontorFinnesIkke -> {
                    /* Vurder om gt kontor skal settes til fallback her */
                    HåndterPersondataEndretSuccess
                }
                is GTKontorFeil -> {
                    val feilmelding = "Kunne ikke håndtere endring i bostedsadresse pga feil ved henting av gt-kontor: ${gtKontorResultat.melding}"
                    HåndterPersondataEndretFail(feilmelding)
                }
            }
        } catch (error: Throwable) {
            return HåndterPersondataEndretFail("Uventet feil ved håndtering av endring i bostedsadresse: ${error.message}", error)
        }
    }

    suspend fun handterEndringForAdressebeskyttelse(
        hendelse: AddressebeskyttelseEndret,
    ): HåndterPersondataEndretResultat {
        try {
            val erSkjermet = when(val skjermetResult = erSkjermetProvider(hendelse.fnr)) {
                is SkjermingFunnet -> skjermetResult.skjermet
                is SkjermingIkkeFunnet -> return HåndterPersondataEndretFail("Kunne ikke hente skjerming ved endring i adressebeskyttelse: ${skjermetResult.melding}")
            }

            val gtKontorResultat = gtKontorProvider(hendelse.fnr, hendelse.erStrengtFortrolig(), erSkjermet)
            return when (gtKontorResultat) {
                is GTKontorFinnesIkke,
                is GTKontorFunnet -> {
                    val gtKontor = getGTKontorOrFallback(gtKontorResultat)
                    tilordneKontor(
                        GTKontorEndretPgaAdressebeskyttelseEndret(
                            KontorTilordning(hendelse.fnr, gtKontor)
                        )
                    )
                    if (hendelse.erGradert()) {
                        tilordneKontor(
                            AOKontorEndretPgaAdressebeskyttelseEndret(
                                KontorTilordning(hendelse.fnr, gtKontor)
                            )
                        )
                    }
                    HåndterPersondataEndretSuccess
                }
                is GTKontorFeil -> {
                    val feilmelding = "Kunne ikke håndtere endring i adressebeskyttelse pga feil ved henting av gt-kontor: ${gtKontorResultat.melding}"
                    HåndterPersondataEndretFail(feilmelding)
                }
            }
        } catch (error: Throwable) {
            return HåndterPersondataEndretFail("Uventet feil ved håndtering av endring i adressebeskyttelse: ${error.message}", error)
        }
    }

    suspend fun handterEndringISkjermingStatus(
        endringISkjermingStatus: SkjermetStatusEndret
    ): Result<EndringISkjermingResult> {
        return runCatching {
            val harStrengtFortroligAdresse = when (val result = harStrengtFortroligAdresseProvider(endringISkjermingStatus.fnr)) {
                is HarStrengtFortroligAdresseIkkeFunnet -> return Result.failure(Exception("Kunne ikke hente adressebeskyttelse ved endring i skjermingstatus: ${result.message}"))
                is HarStrengtFortroligAdresseOppslagFeil -> return Result.failure(Exception("Kunne ikke hente adressebeskyttelse ved endring i skjermingstatus: ${result.message}"))
                is HarStrengtFortroligAdresseFunnet -> result.harStrengtFortroligAdresse
            }

            val gtKontorResultat = gtKontorProvider(endringISkjermingStatus.fnr, harStrengtFortroligAdresse, endringISkjermingStatus.erSkjermet)
            return when (gtKontorResultat) {
                is GTKontorFinnesIkke,
                is GTKontorFunnet -> {
                    val gtKontor = getGTKontorOrFallback(gtKontorResultat)
                    if (endringISkjermingStatus.erSkjermet) {
                        tilordneKontor(
                            AOKontorEndretPgaSkjermingEndret(
                                KontorTilordning(
                                    endringISkjermingStatus.fnr,
                                    gtKontor
                                )
                            )
                        )
                        tilordneKontor(
                            GTKontorEndretPgaSkjermingEndret(
                                KontorTilordning(
                                    endringISkjermingStatus.fnr,
                                    gtKontor
                                )
                            )
                        )
                        Result.success(EndringISkjermingResult.NY_ENHET)
                    } else {
                        Result.success(EndringISkjermingResult.IKKE_NY_ENHET)
                    }

                }
                is GTKontorFeil -> {
                    val feilmelding = "Kunne ikke håndtere endring i skjerming pga feil ved henting av gt-kontor: ${gtKontorResultat.melding}"
                    log.error(feilmelding)
                    return Result.failure(Exception(feilmelding))
                }
            }
        }
    }

    fun getGTKontorOrFallback(gtKontorResultat: GTKontorResultat): KontorId {
        return when (gtKontorResultat) {
            is GTKontorFunnet -> gtKontorResultat.kontorId
            is GTKontorFinnesIkke -> INGEN_GT_KONTOR_FALLBACK
            is GTKontorFeil -> throw IllegalStateException("Kunne ikke hente gt-kontor: ${gtKontorResultat.melding}")
        }
    }
}

