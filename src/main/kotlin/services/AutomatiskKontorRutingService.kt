package no.nav.services

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import no.nav.db.Ident
import no.nav.db.IdentSomKanLagres
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
import no.nav.domain.events.ArenaKontorEndret
import no.nav.domain.events.ArenaKontorFraOppfolgingsbrukerVedOppfolgingStart
import no.nav.domain.events.ArenaKontorVedOppfolgingStart
import no.nav.domain.events.GTKontorEndret
import no.nav.domain.events.OppfolgingsPeriodeStartetFallbackKontorTilordning
import no.nav.domain.events.OppfolgingsPeriodeStartetLokalKontorTilordning
import no.nav.domain.events.OppfolgingsPeriodeStartetSensitivKontorTilordning
import no.nav.domain.events.OppfolgingsperiodeStartetNoeTilordning
import no.nav.domain.externalEvents.AdressebeskyttelseEndret
import no.nav.domain.externalEvents.BostedsadresseEndret
import no.nav.domain.externalEvents.OppfolgingsperiodeStartet
import no.nav.domain.externalEvents.SkjermetStatusEndret
import no.nav.http.client.AlderFunnet
import no.nav.http.client.AlderIkkeFunnet
import no.nav.http.client.AlderOppslagFeil
import no.nav.http.client.AlderResult
import no.nav.http.client.GtForBrukerSuccess
import no.nav.http.client.HarStrengtFortroligAdresseFunnet
import no.nav.http.client.HarStrengtFortroligAdresseIkkeFunnet
import no.nav.http.client.HarStrengtFortroligAdresseOppslagFeil
import no.nav.http.client.HarStrengtFortroligAdresseResult
import no.nav.http.client.SkjermingFunnet
import no.nav.http.client.SkjermingIkkeFunnet
import no.nav.http.client.SkjermingResult
import no.nav.http.client.arbeidssogerregisteret.HentProfileringsResultat
import no.nav.http.client.arbeidssogerregisteret.Profilering
import no.nav.http.client.arbeidssogerregisteret.ProfileringFunnet
import no.nav.http.client.arbeidssogerregisteret.ProfileringIkkeAktuell
import no.nav.http.client.arbeidssogerregisteret.ProfileringIkkeFunnet
import no.nav.http.client.arbeidssogerregisteret.ProfileringsResultat
import no.nav.http.client.arbeidssogerregisteret.ProfileringOppslagFeil
import no.nav.kafka.consumers.EndringISkjermingResult
import no.nav.kafka.consumers.HåndterPersondataEndretFail
import no.nav.kafka.consumers.HåndterPersondataEndretResultat
import no.nav.kafka.consumers.HåndterPersondataEndretSuccess
import no.nav.kafka.consumers.KontorEndringer
import org.slf4j.LoggerFactory
import utils.Outcome
import java.time.Duration
import java.time.ZonedDateTime

sealed class TilordningResultat
sealed class TilordningSuccess : TilordningResultat()
object TilordningSuccessIngenEndring : TilordningSuccess()
data class TilordningSuccessKontorEndret(val kontorEndretEvent: KontorEndringer) : TilordningSuccess()
data class TilordningFeil(val message: String) : TilordningResultat()

class AutomatiskKontorRutingService(
    private val tilordneKontor: suspend (kontorEndretEvent: KontorEndringer) -> Unit,
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
    private val isUnderOppfolgingProvider: suspend (fnr: IdentSomKanLagres) -> OppfolgingsperiodeOppslagResult,
    private val harTilordnetKontorForOppfolgingsperiodeStartetProvider: suspend (fnr: Ident, oppfolgingsperiodeId: OppfolgingsperiodeId) -> Outcome<Boolean>,
) {
    companion object {
        val VIKAFOSSEN = KontorId("2103")
    }

    val log = LoggerFactory.getLogger(this::class.java)

    suspend fun tilordneKontorAutomatisk(
        oppfolgingsperiodeStartet: OppfolgingsperiodeStartet
    ): TilordningResultat {
        try {
            val underOppfolgingResult = isUnderOppfolgingProvider(oppfolgingsperiodeStartet.fnr)
            val (fnr, oppfolgingsperiodeId) = when (underOppfolgingResult) {
                is AktivOppfolgingsperiode -> underOppfolgingResult
                NotUnderOppfolging -> return TilordningSuccessIngenEndring
                is OppfolgingperiodeOppslagFeil -> return TilordningFeil("Feil ved oppslag på oppfolgingsperiode: ${underOppfolgingResult.message}")
            }
            val harBruktOppfolgingsperiodenTidligere = harTilordnetKontorForOppfolgingsperiodeStartetProvider(fnr, oppfolgingsperiodeId)
            when (harBruktOppfolgingsperiodenTidligere) {
                is Outcome.Failure -> return TilordningFeil("Feil ved sjekk av om oppfølgingsperiodeStart har ført til en kontortilordning")
                is Outcome.Success -> if (harBruktOppfolgingsperiodenTidligere.data) return TilordningSuccessIngenEndring
            }
            val (skjermetResult, adressebeskyttelseResult, aldersResult) = coroutineScope {
                val skjermetDeferred = async { erSkjermetProvider(fnr) }
                val adressebeskyttelseDeferred = async { harStrengtFortroligAdresseProvider(fnr) }
                val alderDeferred = async { aldersProvider(fnr) }
                Triple(skjermetDeferred, adressebeskyttelseDeferred, alderDeferred)
            }
            val erSkjermet = when (val skjermetResult = skjermetResult.await()) {
                is SkjermingFunnet -> skjermetResult.skjermet
                is SkjermingIkkeFunnet -> return TilordningFeil("Kunne ikke hente skjerming ved kontortilordning: ${skjermetResult.melding}")
            }
            val harStrengtFortroligAdresse = when (val result = adressebeskyttelseResult.await()) {
                is HarStrengtFortroligAdresseIkkeFunnet -> return TilordningFeil("Kunne ikke hente adressebeskyttelse ved kontortilordning: ${result.message}")
                is HarStrengtFortroligAdresseOppslagFeil -> return TilordningFeil("Kunne ikke hente adressebeskyttelse ved kontortilordning: ${result.message}")
                is HarStrengtFortroligAdresseFunnet -> result.harStrengtFortroligAdresse
            }
            val alder = when (val result = aldersResult.await()) {
                is AlderFunnet -> result.alder
                is AlderIkkeFunnet -> return TilordningFeil("Kunne ikke hente alder: ${result.message}")
                is AlderOppslagFeil -> return TilordningFeil("Henting av alder feilet: ${result.message}")
            }
            val profilering: Profilering = when(oppfolgingsperiodeStartet.erArbeidssøkerRegistrering) {
                true -> {
                    when (val profileringResultat = profileringProvider(fnr)) {
                        is ProfileringFunnet -> profileringResultat
                        is ProfileringIkkeFunnet -> {
                            when (skalForsøkeÅHenteProfileringPåNytt(oppfolgingsperiodeStartet.startDato)) {
                                true -> return TilordningFeil("Fant ikke profilering, men skal forsøke på nytt. Ble registrert for ${Duration.between(oppfolgingsperiodeStartet.startDato, ZonedDateTime.now())} sekunder siden")
                                false -> profileringResultat
                            }
                        }
                        is ProfileringOppslagFeil -> return TilordningFeil("Kunne ikke hente profilering: ${profileringResultat.error.message}")
                    }
                }
                false -> ProfileringIkkeAktuell
            }
            val kontorTilordning = when (val gtKontorResultat = gtKontorProvider(fnr, harStrengtFortroligAdresse, erSkjermet)) {
                is KontorForGtFinnesIkke -> hentTilordningUtenGT(fnr, alder, profilering, oppfolgingsperiodeId, gtKontorResultat)
                is KontorForGtFantLandEllerKontor -> hentTilordning(fnr, gtKontorResultat, alder, profilering, oppfolgingsperiodeId)
                is KontorForGtFeil -> return TilordningFeil("Feil ved henting av gt-kontor: ${gtKontorResultat.melding}")
            }.let { KontorEndringer(aoKontorEndret = it, arenaKontorEndret = arenaKontorEndring(oppfolgingsperiodeStartet, oppfolgingsperiodeId)) }
            tilordneKontor( kontorTilordning)
            return TilordningSuccessKontorEndret(kontorTilordning)

            } catch (e: Exception) {
            return TilordningFeil("Feil ved tilordning av kontor: ${e.message ?: e.toString()}")
        }
    }

    private fun arenaKontorEndring(periodeStartetEvent: OppfolgingsperiodeStartet, oppfolgingsperiodeId: OppfolgingsperiodeId): ArenaKontorEndret? {
        val arenaKontorFraVeilarboppfolging = periodeStartetEvent.startetArenaKontor
        val arenaKontorFraOppfolgingsbruker = periodeStartetEvent.arenaKontorFraOppfolgingsbrukerTopic

        return when {
            arenaKontorFraOppfolgingsbruker != null -> {
                ArenaKontorFraOppfolgingsbrukerVedOppfolgingStart(
                    KontorTilordning(
                        periodeStartetEvent.fnr,
                        arenaKontorFraOppfolgingsbruker.kontor,
                        oppfolgingsperiodeId,
                    ),
                    arenaKontorFraOppfolgingsbruker.sistEndretDato
                )
            }
            arenaKontorFraVeilarboppfolging != null -> {
                ArenaKontorVedOppfolgingStart(
                    KontorTilordning(
                        periodeStartetEvent.fnr,
                        arenaKontorFraVeilarboppfolging,
                        oppfolgingsperiodeId,
                    )
                )
            }
            else -> null
        }
    }

    private fun skalTilNasjonalOppfølgingsEnhet(
        sensitivitet: Sensitivitet,
        profilering: Profilering,
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
        profilering: Profilering,
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
        profilering: Profilering,
        oppfolgingsperiodeId: OppfolgingsperiodeId,
    ): AOKontorEndret {
        val skalTilNOE = skalTilNasjonalOppfølgingsEnhet(gtKontor.sensitivitet(), profilering, alder)
        return when {
            skalTilNOE -> OppfolgingsperiodeStartetNoeTilordning(fnr, oppfolgingsperiodeId)
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
            val oppfolgingsStatus = isUnderOppfolgingProvider(hendelse.ident)
            val oppfolgingsperiodeId = when (oppfolgingsStatus) {
                is NotUnderOppfolging -> {
                    log.info("Skipping bostedsadresse endring - no active oppfølgingsperiode")
                    return HåndterPersondataEndretSuccess(KontorEndringer())
                }
                is OppfolgingperiodeOppslagFeil -> {
                    log.error("Error checking oppfølgingsperiode - ${oppfolgingsStatus.message}")
                    return HåndterPersondataEndretFail(
                        "Error checking oppfølgingsperiode: ${oppfolgingsStatus.message}"
                    )
                }
                is AktivOppfolgingsperiode -> oppfolgingsStatus.periodeId
            }

            val (skjermetResult, adressebeskyttelseResult) = coroutineScope {
                val skjermetDeferred = async { erSkjermetProvider(hendelse.ident) }
                val adressebeskyttelseDeferred = async { harStrengtFortroligAdresseProvider(hendelse.ident) }
                Pair(skjermetDeferred, adressebeskyttelseDeferred)
            }

            val erSkjermet = when (val result = skjermetResult.await()) {
                is SkjermingFunnet -> result.skjermet
                is SkjermingIkkeFunnet -> return HåndterPersondataEndretFail("Kunne ikke hente skjerming ved endring i bostedsadresse: ${result.melding}")
            }
            val harStrengtFortroligAdresse = when (val result = adressebeskyttelseResult.await()) {
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
                    ).let { KontorEndringer(gtKontorEndret = it) }
                    tilordneKontor(gtKontorEndring)
                    HåndterPersondataEndretSuccess(gtKontorEndring)
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
            val oppfolgingsStatus = isUnderOppfolgingProvider(hendelse.ident)
            val oppfolgingsperiodeId = when (oppfolgingsStatus) {
                is NotUnderOppfolging -> {
                    log.info("Skipping adressebeskyttelse endring - no active oppfølgingsperiode")
                    return HåndterPersondataEndretSuccess(KontorEndringer())
                }
                is OppfolgingperiodeOppslagFeil -> {
                    log.error("Error checking oppfølgingsperiode - ${oppfolgingsStatus.message}")
                    return HåndterPersondataEndretFail("Error checking oppfølgingsperiode: ${oppfolgingsStatus.message}")
                }
                is AktivOppfolgingsperiode -> oppfolgingsStatus.periodeId
            }

            val (skjermetResult, adressebeskyttelseResult) = coroutineScope {
                val skjermetDeferred = async { erSkjermetProvider(hendelse.ident) }
                val adressebeskyttelseDeferred = async { harStrengtFortroligAdresseProvider(hendelse.ident) }
                Pair(skjermetDeferred, adressebeskyttelseDeferred)
            }

            val erSkjermet = when (val result = skjermetResult.await()) {
                is SkjermingFunnet -> result.skjermet
                is SkjermingIkkeFunnet -> return HåndterPersondataEndretFail(
                    "Kunne ikke hente skjerming ved endring i adressebeskyttelse: ${result.melding}"
                )
            }

            val harStrengtFortroligAdresse = when (val result = adressebeskyttelseResult.await()) {
                is HarStrengtFortroligAdresseIkkeFunnet ->
                    return HåndterPersondataEndretFail("Kunne ikke hente adressebeskyttelse ved endring i bostedsadresse: ${result.message}")
                is HarStrengtFortroligAdresseOppslagFeil ->
                    return HåndterPersondataEndretFail("Kunne ikke hente adressebeskyttelse ved endring i bostedsadresse: ${result.message}")
                is HarStrengtFortroligAdresseFunnet -> result.harStrengtFortroligAdresse
            }

            val gtKontorResultat = gtKontorProvider(hendelse.ident, harStrengtFortroligAdresse, erSkjermet)
            return when (gtKontorResultat) {
                is KontorForGtSuccess -> {
                    val gtKontor = getGTKontorOrFallback(gtKontorResultat)
                    val gtKontorEndring = GTKontorEndret.endretPgaAdressebeskyttelseEndret(
                        KontorTilordning(hendelse.ident, gtKontor, oppfolgingsperiodeId),
                        harStrengtFortroligAdresse,
                        gtKontorResultat.gt()
                    )

                    if (harStrengtFortroligAdresse.value) {
                        val kontorEndringer = AOKontorEndretPgaAdressebeskyttelseEndret(
                            KontorTilordning(hendelse.ident, gtKontor, oppfolgingsperiodeId)
                        ).let { KontorEndringer(aoKontorEndret = it, gtKontorEndret = gtKontorEndring) }
                        tilordneKontor(kontorEndringer)
                        HåndterPersondataEndretSuccess(kontorEndringer)
                    } else {
                        val kontorEndring = KontorEndringer(gtKontorEndret = gtKontorEndring)
                        tilordneKontor( kontorEndring)
                        HåndterPersondataEndretSuccess(KontorEndringer(gtKontorEndret = gtKontorEndring))
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
            val oppfolgingsperiodeId = when (val result = isUnderOppfolgingProvider(endringISkjermingStatus.fnr)) {
                is AktivOppfolgingsperiode -> result.periodeId
                NotUnderOppfolging -> return Result.success(EndringISkjermingResult(KontorEndringer()))
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
                    if (endringISkjermingStatus.erSkjermet.value) {
                        val aoKontorEndring = AOKontorEndretPgaSkjermingEndret(
                            KontorTilordning(endringISkjermingStatus.fnr, gtKontor, oppfolgingsperiodeId)
                        )
                        val kontorEndringer = KontorEndringer(aoKontorEndret = aoKontorEndring, gtKontorEndret = gtKontorEndring)
                        tilordneKontor(kontorEndringer)
                        Result.success(EndringISkjermingResult(kontorEndringer))
                    } else {
                        val kontorEndringer = KontorEndringer(gtKontorEndret = gtKontorEndring)
                        tilordneKontor(kontorEndringer)
                        Result.success(EndringISkjermingResult(kontorEndringer))
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

    private fun skalForsøkeÅHenteProfileringPåNytt(oppfolgingsperiodeStartet: ZonedDateTime): Boolean {
        val tidSidenBrukerBleRegistrert = Duration.between(oppfolgingsperiodeStartet, ZonedDateTime.now())
        val forventetForsinkelsePåProfilering = Duration.ofSeconds(5)
        val feilmargin = Duration.ofSeconds(5)
        return tidSidenBrukerBleRegistrert < (forventetForsinkelsePåProfilering + feilmargin)
    }
}

