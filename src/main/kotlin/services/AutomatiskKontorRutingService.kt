package no.nav.services

import no.nav.db.Ident
import no.nav.domain.HarSkjerming
import no.nav.domain.HarStrengtFortroligAdresse
import no.nav.domain.INGEN_GT_KONTOR_FALLBACK
import no.nav.domain.KontorId
import no.nav.domain.KontorTilordning
import no.nav.domain.OppfolgingsperiodeId
import no.nav.domain.Sensitivitet
import no.nav.domain.events.AOKontorEndret
import no.nav.domain.events.AOKontorEndretPgaAdressebeskyttelseEndret
import no.nav.domain.events.AOKontorEndretPgaSkjermingEndret
import no.nav.domain.events.GTKontorEndret
import no.nav.domain.events.KontorEndretEvent
import no.nav.domain.events.OppfolgingsPeriodeStartetFallbackKontorTilordning
import no.nav.domain.events.OppfolgingsPeriodeStartetLokalKontorTilordning
import no.nav.domain.events.OppfolgingsPeriodeStartetSensitivKontorTilordning
import no.nav.domain.events.OppfolgingsperiodeStartetNoeTilordning
import no.nav.domain.externalEvents.AdressebeskyttelseEndret
import no.nav.domain.externalEvents.BostedsadresseEndret
import no.nav.domain.externalEvents.OppfolgingsperiodeAvsluttet
import no.nav.domain.externalEvents.OppfolgingsperiodeEndret
import no.nav.domain.externalEvents.SkjermetStatusEndret
import no.nav.domain.externalEvents.erStrengtFortrolig
import no.nav.http.client.AlderFunnet
import no.nav.http.client.AlderIkkeFunnet
import no.nav.http.client.AlderResult
import no.nav.http.client.FnrFunnet
import no.nav.http.client.FnrResult
import no.nav.http.client.GtForBrukerSuccess
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
data class TilordningSuccessKontorEndret(val kontorEndretEvent: KontorEndretEvent) : TilordningSuccess()
data class TilordningFeil(val message: String) : TilordningResultat()

class AutomatiskKontorRutingService(
    private val tilordneKontor: suspend (kontorEndretEvent: KontorEndretEvent) -> Unit,
    private val gtKontorProvider:
    suspend (
        fnr: Ident,
        strengtFortroligAdresse: HarStrengtFortroligAdresse,
        skjermet: HarSkjerming
    ) -> KontorForGtResultat,
    private val aldersProvider: suspend (fnr: Ident) -> AlderResult,
    private val profileringProvider: suspend (fnr: Ident) -> HentProfileringsResultat,
    private val erSkjermetProvider: suspend (fnr: Ident) -> SkjermingResult,
    private val harStrengtFortroligAdresseProvider:
    suspend (fnr: Ident) -> HarStrengtFortroligAdresseResult,
    private val isUnderOppfolgingProvider: suspend (fnr: FnrResult) -> OppfolgingsperiodeOppslagResult,
) {
    companion object {
        val VIKAFOSSEN = KontorId("2103")
    }

    val log = LoggerFactory.getLogger(this::class.java)

    suspend fun tilordneKontorAutomatisk(
        oppfolgingsperiodeEndret: OppfolgingsperiodeEndret
    ): TilordningResultat {
        if (oppfolgingsperiodeEndret is OppfolgingsperiodeAvsluttet) return TilordningSuccessIngenEndring
        try {
            val underOppfolgingResult = isUnderOppfolgingProvider(FnrFunnet(oppfolgingsperiodeEndret.fnr))
            val oppfolgingsperiode = when (underOppfolgingResult) {
                is AktivOppfolgingsperiode -> underOppfolgingResult
                NotUnderOppfolging -> return TilordningSuccessIngenEndring
                is OppfolgingperiodeOppslagFeil -> return TilordningFeil("Feil ved oppslag på fnr: ${underOppfolgingResult.message}")
            }
            val oppfolgingsperiodeId = oppfolgingsperiode.periodeId
            val fnr = oppfolgingsperiode.fnr
            val erSkjermet = when (val skjermetResult = erSkjermetProvider(fnr)) {
                is SkjermingFunnet -> skjermetResult.skjermet
                is SkjermingIkkeFunnet -> return TilordningFeil("Kunne ikke hente skjerming ved kontortilordning: ${skjermetResult.melding}")
            }
            val harStrengtFortroligAdresse = when (val result = harStrengtFortroligAdresseProvider(fnr)) {
                is HarStrengtFortroligAdresseIkkeFunnet -> return TilordningFeil("Kunne ikke hente adressebeskyttelse ved kontortilordning: ${result.message}")
                is HarStrengtFortroligAdresseOppslagFeil -> return TilordningFeil("Kunne ikke hente adressebeskyttelse ved kontortilordning: ${result.message}")
                is HarStrengtFortroligAdresseFunnet -> result.harStrengtFortroligAdresse
            }
            val alder = when (val result = aldersProvider(fnr)) {
                is AlderFunnet -> result.alder
                is AlderIkkeFunnet -> return TilordningFeil("Kunne ikke hente alder: ${result.message}")
            }
            val gtKontorResultat = gtKontorProvider(fnr, harStrengtFortroligAdresse, erSkjermet)

            val kontorTilordning = when (gtKontorResultat) {
                is KontorForGtFinnesIkke -> hentTilordningUtenGT(fnr, alder, profileringProvider(fnr), oppfolgingsperiodeId, gtKontorResultat)
                is KontorForGtFantLandEllerKontor -> hentTilordning(fnr, gtKontorResultat, alder, profileringProvider(fnr), oppfolgingsperiodeId)
                is KontorForGtFeil -> return TilordningFeil("Feil ved henting av gt-kontor: ${gtKontorResultat.melding}")
            }
            tilordneKontor(kontorTilordning)
            return TilordningSuccessKontorEndret(kontorTilordning)
        } catch (e: Exception) {
            return TilordningFeil("Feil ved tilordning av kontor: ${e.message ?: e.toString()}")
        }
    }

    private fun skalTilNasjonalOppfølgingsEnhet(
        sensitivitet: Sensitivitet,
        profilering: HentProfileringsResultat,
        alder: Int
    ): Boolean {
        return !sensitivitet.erSensitiv() &&
                profilering is ProfileringFunnet &&
                profilering.profilering == ProfileringsResultat.ANTATT_GODE_MULIGHETER &&
                alder in 31..59
    }

    private fun hentTilordningUtenGT(
        fnr: Ident,
        alder: Int,
        profilering: HentProfileringsResultat,
        oppfolgingsperiodeId: OppfolgingsperiodeId,
        gtResultat: KontorForGtFinnesIkke
    ): AOKontorEndret {
        return when {
            skalTilNasjonalOppfølgingsEnhet(gtResultat.sensitivitet(), profilering, alder) -> OppfolgingsperiodeStartetNoeTilordning(fnr, oppfolgingsperiodeId)
            gtResultat.sensitivitet().erSensitiv() -> {
                if (gtResultat.erStrengtFortrolig()) {
                    OppfolgingsPeriodeStartetSensitivKontorTilordning(
                        KontorTilordning(fnr, VIKAFOSSEN, oppfolgingsperiodeId),
                        gtResultat.sensitivitet(),
                        gtResultat
                    )
                }
                else OppfolgingsPeriodeStartetFallbackKontorTilordning(
                    fnr,
                    oppfolgingsperiodeId,
                    gtResultat.sensitivitet(),
                )
            }
            else -> {
                OppfolgingsPeriodeStartetFallbackKontorTilordning(
                    fnr,
                    oppfolgingsperiodeId,
                    gtResultat.sensitivitet()
                )
            }
        }
    }

    private fun hentTilordning(
        fnr: Ident,
        gtKontor: KontorForGtFantLandEllerKontor,
        alder: Int,
        profilering: HentProfileringsResultat,
        oppfolgingsperiodeId: OppfolgingsperiodeId,
    ): AOKontorEndret {
        val skalTilNOE = skalTilNasjonalOppfølgingsEnhet(gtKontor.sensitivitet(), profilering, alder)
        return when {
            skalTilNOE ->
                OppfolgingsperiodeStartetNoeTilordning(fnr, oppfolgingsperiodeId)
            gtKontor.sensitivitet().erSensitiv() -> {
                when (gtKontor) {
                    is KontorForGtNrFantKontor ->
                        OppfolgingsPeriodeStartetSensitivKontorTilordning(
                            KontorTilordning(fnr, gtKontor.kontorId, oppfolgingsperiodeId),
                            gtKontor
                        )
                    is KontorForGtFantLand ->
                        if (gtKontor.erStrengtFortrolig()) {
                            OppfolgingsPeriodeStartetSensitivKontorTilordning(
                                KontorTilordning(fnr, VIKAFOSSEN, oppfolgingsperiodeId),
                                gtKontor
                            )
                        } else {
                            throw IllegalStateException(
                                "Vi håndterer ikke skjermede brukere uten geografisk tilknytning"
                            )
                        }
                }
            }
            else -> {
                when (gtKontor) {
                    is KontorForGtNrFantKontor ->
                        OppfolgingsPeriodeStartetLokalKontorTilordning(
                            KontorTilordning(fnr, gtKontor.kontorId, oppfolgingsperiodeId),
                            gtKontor.sensitivitet()
                        )
                    is KontorForGtFantLand ->
                        OppfolgingsPeriodeStartetFallbackKontorTilordning(
                            fnr,
                            oppfolgingsperiodeId,
                            gtKontor.sensitivitet()
                        )
                }
            }
        }
    }

    suspend fun handterEndringForBostedsadresse(
        hendelse: BostedsadresseEndret,
    ): HåndterPersondataEndretResultat {
        try {
            val oppfolgingsStatus = isUnderOppfolgingProvider(FnrFunnet(hendelse.ident))
            val oppfolgingsperiodeId = when (oppfolgingsStatus) {
                is NotUnderOppfolging -> {
                    log.info("Skipping bostedsadresse endring - no active oppfølgingsperiode")
                    return HåndterPersondataEndretSuccess(emptyList())
                }
                is OppfolgingperiodeOppslagFeil -> {
                    log.error("Error checking oppfølgingsperiode - ${oppfolgingsStatus.message}")
                    return HåndterPersondataEndretFail(
                        "Error checking oppfølgingsperiode: ${oppfolgingsStatus.message}"
                    )
                }
                is AktivOppfolgingsperiode -> oppfolgingsStatus.periodeId
            }
            val erSkjermet =
                when (val skjermetResult = erSkjermetProvider(hendelse.ident)) {
                    is SkjermingFunnet -> skjermetResult.skjermet
                    is SkjermingIkkeFunnet ->
                        return HåndterPersondataEndretFail(skjermetResult.melding)
                }
            val harStrengtFortroligAdresse =
                when (val result = harStrengtFortroligAdresseProvider(hendelse.ident)) {
                    is HarStrengtFortroligAdresseIkkeFunnet ->
                        return HåndterPersondataEndretFail("Kunne ikke hente adressebeskyttelse ved endring i bostedsadresse: ${result.message}")
                    is HarStrengtFortroligAdresseOppslagFeil ->
                        return HåndterPersondataEndretFail("Kunne ikke hente adressebeskyttelse ved endring i bostedsadresse: ${result.message}")
                    is HarStrengtFortroligAdresseFunnet -> result.harStrengtFortroligAdresse
                }

            val gtKontorResultat = gtKontorProvider(hendelse.ident, harStrengtFortroligAdresse, erSkjermet)
            return when (gtKontorResultat) {
                is KontorForGtSuccess -> {
                    val kontorId = when (gtKontorResultat) {
                        is KontorForGtFantLand,
                        is KontorForGtFinnesIkke -> INGEN_GT_KONTOR_FALLBACK
                        is KontorForGtNrFantDefaultKontor -> gtKontorResultat.kontorId
                        is KontorForGtNrFantFallbackKontorForManglendeGt -> gtKontorResultat.kontorId
                    }
                    val gtKontorEndring = GTKontorEndret.endretPgaBostedsadresseEndret(
                        KontorTilordning(hendelse.ident, kontorId, oppfolgingsperiodeId),
                        gtKontorResultat.gt()
                    )
                    tilordneKontor(gtKontorEndring)
                    HåndterPersondataEndretSuccess(listOf(gtKontorEndring))
                }
                is KontorForGtFeil -> HåndterPersondataEndretFail("Kunne ikke håndtere endring i bostedsadresse pga feil ved henting av gt-kontor: ${gtKontorResultat.melding}")
            }
        } catch (error: Throwable) {
            return HåndterPersondataEndretFail(
                "Uventet feil ved håndtering av endring i bostedsadresse: ${error.message}",
                error
            )
        }
    }

    suspend fun handterEndringForAdressebeskyttelse(
        hendelse: AdressebeskyttelseEndret,
    ): HåndterPersondataEndretResultat {
        try {
            // Check oppfølgingsperiode status first
            val oppfolgingsStatus = isUnderOppfolgingProvider(FnrFunnet(hendelse.ident))
            val oppfolgingsperiodeId = when (oppfolgingsStatus) {
                is NotUnderOppfolging -> {
                    log.info("Skipping adressebeskyttelse endring - no active oppfølgingsperiode")
                    return HåndterPersondataEndretSuccess(emptyList())
                }
                is OppfolgingperiodeOppslagFeil -> {
                    log.error("Error checking oppfølgingsperiode - ${oppfolgingsStatus.message}")
                    return HåndterPersondataEndretFail("Error checking oppfølgingsperiode: ${oppfolgingsStatus.message}")
                }
                is AktivOppfolgingsperiode -> oppfolgingsStatus.periodeId
            }

            val erSkjermet =
                when (val skjermetResult = erSkjermetProvider(hendelse.ident)) {
                    is SkjermingFunnet -> skjermetResult.skjermet
                    is SkjermingIkkeFunnet ->
                        return HåndterPersondataEndretFail(
                            "Kunne ikke hente skjerming ved endring i adressebeskyttelse: ${skjermetResult.melding}"
                        )
                }

            val gtKontorResultat = gtKontorProvider(hendelse.ident, hendelse.erStrengtFortrolig(), erSkjermet)
            return when (gtKontorResultat) {
                is KontorForGtSuccess -> {
                    val gtKontor = getGTKontorOrFallback(gtKontorResultat)
                    val gtKontorEndring = GTKontorEndret.endretPgaAdressebeskyttelseEndret(
                        KontorTilordning(hendelse.ident, gtKontor, oppfolgingsperiodeId),
                        hendelse.erStrengtFortrolig(),
                        gtKontorResultat.gt()
                    )
                    tilordneKontor(gtKontorEndring)
                    if (hendelse.erStrengtFortrolig().value) {
                        val aoKontorEndring = AOKontorEndretPgaAdressebeskyttelseEndret(
                            KontorTilordning(hendelse.ident, gtKontor, oppfolgingsperiodeId)
                        )
                        tilordneKontor(aoKontorEndring)
                        HåndterPersondataEndretSuccess(listOf(gtKontorEndring, aoKontorEndring))
                    } else {
                        HåndterPersondataEndretSuccess(listOf(gtKontorEndring))
                    }
                }
                is KontorForGtFeil -> {
                    val feilmelding = "Kunne ikke håndtere endring i adressebeskyttelse pga feil ved henting av gt-kontor: ${gtKontorResultat.melding}"
                    HåndterPersondataEndretFail(feilmelding)
                }

            }
        } catch (error: Throwable) {
            return HåndterPersondataEndretFail(
                "Uventet feil ved håndtering av endring i adressebeskyttelse: ${error.message}",
                error
            )
        }
    }

    suspend fun handterEndringISkjermingStatus(
        endringISkjermingStatus: SkjermetStatusEndret
    ): Result<EndringISkjermingResult> {
        return runCatching {
            val oppfolgingsperiodeId = when (val result = isUnderOppfolgingProvider(FnrFunnet(endringISkjermingStatus.fnr))) {
                is AktivOppfolgingsperiode -> result.periodeId
                NotUnderOppfolging -> return Result.success(EndringISkjermingResult(emptyList()))
                is OppfolgingperiodeOppslagFeil ->
                    return Result.failure(
                        Exception("Kunne håndtere endring i skjerming pga feil ved henting av oppfolgingsstatus: ${result.message}")
                    )
            }

            val harStrengtFortroligAdresse =
                when (val result = harStrengtFortroligAdresseProvider(endringISkjermingStatus.fnr)) {
                    is HarStrengtFortroligAdresseIkkeFunnet ->
                        return Result.failure(
                            Exception(
                                "Kunne ikke hente adressebeskyttelse ved endring i skjermingstatus: ${result.message}"
                            )
                        )
                    is HarStrengtFortroligAdresseOppslagFeil ->
                        return Result.failure(
                            Exception(
                                "Kunne ikke hente adressebeskyttelse ved endring i skjermingstatus: ${result.message}"
                            )
                        )
                    is HarStrengtFortroligAdresseFunnet -> result.harStrengtFortroligAdresse
                }

            val gtKontorResultat = gtKontorProvider(
                endringISkjermingStatus.fnr,
                harStrengtFortroligAdresse,
                endringISkjermingStatus.erSkjermet
            )
            return when (gtKontorResultat) {
                is KontorForGtSuccess -> {
                    val gtKontor = getGTKontorOrFallback(gtKontorResultat)
                    val gtKontorEndring = endretPgaSkjermingEndret(
                        endringISkjermingStatus,
                        gtKontor,
                        oppfolgingsperiodeId,
                        gtKontorResultat.gt()
                    )
                    tilordneKontor(gtKontorEndring)
                    if (endringISkjermingStatus.erSkjermet.value) {
                        val aoKontorEndring = AOKontorEndretPgaSkjermingEndret(
                            KontorTilordning(endringISkjermingStatus.fnr, gtKontor, oppfolgingsperiodeId)
                        )
                        tilordneKontor(aoKontorEndring)
                        Result.success(
                            EndringISkjermingResult(listOf(gtKontorEndring, aoKontorEndring))
                        )
                    } else {
                        Result.success(EndringISkjermingResult(listOf(gtKontorEndring)))
                    }
                }
                is KontorForGtFeil -> {
                    val feilmelding =
                        "Kunne ikke håndtere endring i skjerming pga feil ved henting av gt-kontor: ${gtKontorResultat.melding}"
                    log.error(feilmelding)
                    return Result.failure(Exception(feilmelding))
                }
            }
        }
    }

    fun getGTKontorOrFallback(gtKontorResultat: KontorForGtSuccess): KontorId {
        return when (gtKontorResultat) {
            is KontorForGtNrFantKontor -> gtKontorResultat.kontorId
            is KontorForGtFinnesIkke,
            is KontorForGtFantLand -> {
                if (gtKontorResultat.sensitivitet().strengtFortroligAdresse.value) {
                    VIKAFOSSEN
                } else if (gtKontorResultat.sensitivitet().skjermet.value) {
                    throw IllegalStateException(
                        "Skjermede brukere uten geografisk tilknytning eller med land som GT kan ikke tilordnes kontor"
                    )
                } else INGEN_GT_KONTOR_FALLBACK
            }
        }
    }

    fun endretPgaSkjermingEndret(
        endringISkjermingStatus: SkjermetStatusEndret,
        gtKontor: KontorId,
        oppfolgingsperiodeId: OppfolgingsperiodeId,
        gtForBruker: GtForBrukerSuccess
    ): GTKontorEndret = GTKontorEndret.endretPgaSkjermingEndret(
        KontorTilordning(endringISkjermingStatus.fnr, gtKontor, oppfolgingsperiodeId),
        endringISkjermingStatus.erSkjermet,
        gtForBruker
    )
}

