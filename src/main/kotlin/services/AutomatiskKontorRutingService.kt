package no.nav.services

import domain.Systemnavn
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
import no.nav.domain.events.GTKontorEndret
import no.nav.domain.events.OppfolgingsPeriodeStartetFallbackKontorTilordning
import no.nav.domain.events.OppfolgingsPeriodeStartetLokalKontorTilordning
import no.nav.domain.events.OppfolgingsPeriodeStartetSensitivKontorTilordning
import no.nav.domain.events.OppfolgingsperiodeStartetNoeTilordning
import no.nav.domain.externalEvents.AdressebeskyttelseEndret
import no.nav.domain.externalEvents.BostedsadresseEndret
import no.nav.domain.externalEvents.OppfolgingsperiodeStartet
import no.nav.domain.externalEvents.SkjermetStatusEndret
import no.nav.http.client.AlderFunnet as PdlAlderFunnet
import no.nav.http.client.AlderIkkeFunnet
import no.nav.http.client.AlderOppslagFeil
import no.nav.http.client.AlderResult as PdlAlderResult
import domain.gtForBruker.GtForBrukerSuccess
import domain.gtForBruker.GtLandForBrukerFunnet
import domain.kontorForGt.KontorForGtFeil
import domain.kontorForGt.KontorForGtFantIkkeKontor
import domain.kontorForGt.KontorForGtFantDefaultKontor
import domain.kontorForGt.KontorForGtNrFantFallbackKontorForManglendeGt
import domain.kontorForGt.KontorForGtFantKontor
import domain.kontorForGt.KontorForGtFantKontorForArbeidsgiverAdresse
import domain.kontorForGt.KontorForGtResultat
import domain.kontorForGt.KontorForGtSuccess
import no.nav.domain.events.OppfolgingsperiodeStartetManuellTilordning
import no.nav.domain.externalEvents.KontorOverstyring
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
import no.nav.kafka.consumers.EndringISkjermingSuccess
import no.nav.kafka.consumers.HåndterPersondataEndretFail
import no.nav.kafka.consumers.HåndterPersondataEndretResultat
import no.nav.kafka.consumers.HåndterPersondataEndretSuccess
import no.nav.kafka.consumers.KontorEndringer
import no.nav.services.AutomatiskKontorRutingService.Companion.VIKAFOSSEN
import org.slf4j.LoggerFactory
import utils.Outcome
import java.time.Duration
import java.time.ZonedDateTime
import no.nav.domain.ArbeidsoppfolgingsKontor
import no.nav.domain.System
import no.nav.domain.events.AOKontorEndretPgaNorskGT
import no.nav.kafka.consumers.EndringISkjermingBehandlingFeilet
import no.nav.kafka.consumers.EndringISkjermingBrukerIkkeUnderOppfølging
import no.nav.kafka.consumers.EndringISkjermingResult
import no.nav.kafka.consumers.HåndterPersondataEndretIkkeUnderOppfølging

sealed class TilordningResultat
sealed class TilordningSuccess : TilordningResultat()
object TilordningSuccessIngenEndring : TilordningSuccess()
data class TilordningSuccessKontorEndret(val kontorEndretEvent: KontorEndringer) : TilordningSuccess()
data class TilordningFeil(val message: String) : TilordningResultat()
data class TilordningRetry(val message: String) : TilordningResultat()

sealed class AlderResult
class AlderFunnet(val alder: Int): AlderResult()
object AlderIkkeRelevant: AlderResult()

data class AutomatiskKontorRutingService(
    private val hentKontorForGt:
    suspend (
        fnr: IdentSomKanLagres,
        strengtFortroligAdresse: HarStrengtFortroligAdresse,
        skjermet: HarSkjerming
    ) -> KontorForGtResultat,
    private val hentAlder: suspend (fnr: Ident) -> PdlAlderResult,
    private val hentProfilering: suspend (fnr: Ident) -> HentProfileringsResultat,
    private val hentSkjerming: suspend (fnr: Ident) -> SkjermingResult,
    private val hentHarStrengtFortroligAdresse:
    suspend (fnr: Ident) -> HarStrengtFortroligAdresseResult,
    private val hentGjeldendeOppfolgingsperiode: suspend (fnr: IdentSomKanLagres) -> OppfolgingsperiodeOppslagResult,
    private val harAlleredeTilordnetAoKontorForOppfolgingsperiode: suspend (fnr: Ident, oppfolgingsperiodeId: OppfolgingsperiodeId) -> Outcome<Boolean>,
    private val hentAoKontor: suspend (IdentSomKanLagres) -> ArbeidsoppfolgingsKontor?,
) {
    companion object {
        val VIKAFOSSEN = KontorId("2103")
    }

    val log = LoggerFactory.getLogger(this::class.java)

    suspend fun tilordneKontorAutomatiskVedStartOppfolging(
        oppfolgingsperiodeStartet: OppfolgingsperiodeStartet
    ): TilordningResultat {
        try {
            val underOppfolgingResult = hentGjeldendeOppfolgingsperiode(oppfolgingsperiodeStartet.fnr)
            val (ident, _, oppfolgingsperiodeId) = when (underOppfolgingResult) {
                is AktivOppfolgingsperiode -> underOppfolgingResult
                NotUnderOppfolging -> return TilordningSuccessIngenEndring
                is OppfolgingperiodeOppslagFeil -> return TilordningFeil("Feil ved oppslag på oppfolgingsperiode: ${underOppfolgingResult.message}")
            }
            val harAlleredeTilordnetAoKontorForOppfolgingsperiode =
                harAlleredeTilordnetAoKontorForOppfolgingsperiode(ident, oppfolgingsperiodeId)
            when (harAlleredeTilordnetAoKontorForOppfolgingsperiode) {
                is Outcome.Failure -> return TilordningFeil("Feil ved sjekk av om vi allerede har tilordnet et AO-kontor for oppfølgingsperioden")
                is Outcome.Success -> if (harAlleredeTilordnetAoKontorForOppfolgingsperiode.data) return TilordningSuccessIngenEndring
            }

            return tilordneKontorAutomatisk(
                ident,
                oppfolgingsperiodeId,
                oppfolgingsperiodeStartet.erArbeidssøkerRegistrering,
                oppfolgingsperiodeStartet.startDato,
                oppfolgingsperiodeStartet.kontorOverstyring
            )

        } catch (e: Exception) {
            return TilordningFeil("Feil ved tilordning av kontor: ${e.message ?: e.toString()}")
        }
    }

    suspend fun tilordneKontorAutomatisk(
        ident: IdentSomKanLagres,
        oppfolgingsperiodeId: OppfolgingsperiodeId,
        erArbeidssøkerRegistrering: Boolean,
        oppfolgingStartDato: ZonedDateTime,
        manueltSattKontor : KontorOverstyring?
    ): TilordningResultat {
        try {

            val (skjermetResult, adressebeskyttelseResult, aldersResult) = coroutineScope {
                val skjermetDeferred = async { hentSkjerming(ident) }
                val adressebeskyttelseDeferred = async { hentHarStrengtFortroligAdresse(ident) }
                val alderDeferred = async { hentAlder(ident) }
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
                is PdlAlderFunnet -> AlderFunnet(result.alder)
                is AlderIkkeFunnet ->
                    if (manueltSattKontor != null) AlderIkkeRelevant
                    else return TilordningFeil("Kunne ikke hente alder: ${result.message}")
                is AlderOppslagFeil ->
                    if (manueltSattKontor != null) AlderIkkeRelevant
                    else return TilordningFeil("Henting av alder feilet: ${result.message}")
            }
            val profilering: Profilering = when (erArbeidssøkerRegistrering && manueltSattKontor == null) {
                true -> {
                    when (val profileringResultat = hentProfilering(ident)) {
                        is ProfileringFunnet -> profileringResultat
                        is ProfileringIkkeFunnet -> {
                            when (skalForsøkeÅHenteProfileringPåNytt(oppfolgingStartDato)) {
                                true -> return TilordningRetry("Fant ikke profilering, men skal forsøke på nytt. Ble registrert for kort tid siden")
                                false -> profileringResultat
                                    .also {
                                        log.info("Tilordner bruker kontor uten at profilering ble funnet: ${profileringResultat.melding}")
                                    }
                            }
                        }
                        is ProfileringOppslagFeil -> return TilordningFeil("Kunne ikke hente profilering: ${profileringResultat.error.message}")
                    }
                }
                false -> ProfileringIkkeAktuell
            }
            val gtKontorResultat = hentKontorForGt(ident, harStrengtFortroligAdresse, erSkjermet)
            val kontorTilordning = when (gtKontorResultat) {
                is KontorForGtFeil -> return TilordningFeil("Feil ved henting av gt-kontor: ${gtKontorResultat.melding}")
                is KontorForGtSuccess -> {
                    velgKontorForBruker(
                        fnr = ident,
                        gtResultat = gtKontorResultat,
                        alder = alder,
                        profilering = profilering,
                        oppfolgingsperiodeId = oppfolgingsperiodeId,
                        kontorOverstyring = manueltSattKontor
                    )
                }
            }
                .let {
                    KontorEndringer(
                        aoKontorEndret = it,
                        gtKontorEndret = gtKontorResultat.toGtKontorEndret(ident, oppfolgingsperiodeId)
                    )
                }
            return TilordningSuccessKontorEndret(kontorTilordning)
        } catch (e: Exception) {
            return TilordningFeil("Feil ved tilordning av kontor: ${e.message ?: e.toString()}")
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
                alder in 30..66
    }

    fun erOverstyrtOgIkkeSensitiv(kontorOverstyring: KontorOverstyring?, erSensitiv: Boolean): Boolean {
        return kontorOverstyring != null && !erSensitiv
    }

    private fun velgKontorForBruker(
        fnr: IdentSomKanLagres,
        alder: AlderResult,
        profilering: Profilering,
        oppfolgingsperiodeId: OppfolgingsperiodeId,
        gtResultat: KontorForGtSuccess,
        kontorOverstyring: KontorOverstyring?
    ): AOKontorEndret {
        val erSensitiv = gtResultat.sensitivitet().erSensitiv()
        return when {
            kontorOverstyring != null && erOverstyrtOgIkkeSensitiv(kontorOverstyring, erSensitiv) -> OppfolgingsperiodeStartetManuellTilordning(
                fnr,
                oppfolgingsperiodeId,
                kontorOverstyring
            )
            alder is AlderFunnet && skalTilNasjonalOppfølgingsEnhet(
                gtResultat.sensitivitet(),
                profilering, alder.alder
            ) -> OppfolgingsperiodeStartetNoeTilordning(fnr, oppfolgingsperiodeId)
            else -> {
                when (gtResultat) {
                    is KontorForGtFantKontor -> {
                        when {
                            erSensitiv -> {
                                if (gtResultat.erStrengtFortrolig()) {
                                    OppfolgingsPeriodeStartetSensitivKontorTilordning(
                                        KontorTilordning(fnr, VIKAFOSSEN, oppfolgingsperiodeId),
                                        gtResultat.sensitivitet(),
                                        gtResultat
                                    )
                                } else {
                                    OppfolgingsPeriodeStartetSensitivKontorTilordning(
                                        KontorTilordning(fnr, gtResultat.kontorId, oppfolgingsperiodeId),
                                        gtResultat.sensitivitet(),
                                        gtResultat
                                    )
                                }
                            }
                            else -> OppfolgingsPeriodeStartetLokalKontorTilordning(
                                KontorTilordning(fnr, gtResultat.kontorId, oppfolgingsperiodeId),
                                gtResultat
                            )
                        }

                    }
                    is KontorForGtFantIkkeKontor -> {
                        when {
                            gtResultat.erStrengtFortrolig() -> {
                                OppfolgingsPeriodeStartetSensitivKontorTilordning(
                                    KontorTilordning(fnr, VIKAFOSSEN, oppfolgingsperiodeId),
                                    gtResultat.sensitivitet(),
                                    gtResultat
                                )
                            }
                            else -> OppfolgingsPeriodeStartetFallbackKontorTilordning(
                                fnr,
                                oppfolgingsperiodeId,
                                gtResultat.sensitivitet(),
                                gtResultat
                            )
                        }

                    }
                }
            }
        }
    }

    suspend fun handterEndringForBostedsadresse(
        hendelse: BostedsadresseEndret,
    ): HåndterPersondataEndretResultat {
        try {
            val oppfolgingsStatus = hentGjeldendeOppfolgingsperiode(hendelse.ident)
            val oppfolgingsperiodeId = when (oppfolgingsStatus) {
                is NotUnderOppfolging -> {
                    log.info("Skipping bostedsadresse endring - no active oppfølgingsperiode")
                    return HåndterPersondataEndretIkkeUnderOppfølging
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
                val skjermetDeferred = async { hentSkjerming(hendelse.ident) }
                val adressebeskyttelseDeferred = async { hentHarStrengtFortroligAdresse(hendelse.ident) }
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

            val gtKontorResultat = hentKontorForGt(hendelse.ident, harStrengtFortroligAdresse, erSkjermet)
            return when (gtKontorResultat) {
                is KontorForGtSuccess -> {
                    val kontorId = when (gtKontorResultat) {
                        is KontorForGtFantIkkeKontor -> INGEN_GT_KONTOR_FALLBACK
                        is KontorForGtFantDefaultKontor -> gtKontorResultat.kontorId
                        is KontorForGtNrFantFallbackKontorForManglendeGt -> gtKontorResultat.kontorId
                        is KontorForGtFantKontorForArbeidsgiverAdresse -> gtKontorResultat.kontorId
                    }
                    val gtKontorEndring = GTKontorEndret.endretPgaBostedsadresseEndret(
                        KontorTilordning(hendelse.ident, kontorId, oppfolgingsperiodeId),
                        gtKontorResultat.gt()
                    )
                    val skalEndreOppfolgingskontor = skalEndreOppfolgingskontorVedEndretGtKontor(gtKontorResultat, hendelse.ident)
                    val kontorendringer = if (skalEndreOppfolgingskontor) {
                        AOKontorEndretPgaNorskGT(
                            kontorTilordning = gtKontorEndring.kontorTilordning,
                            registrant = System(Systemnavn.PDL),
                        ).let { KontorEndringer(aoKontorEndret = it, gtKontorEndret = gtKontorEndring) }
                    } else {
                        KontorEndringer(gtKontorEndret = gtKontorEndring)
                    }
                    HåndterPersondataEndretSuccess(kontorendringer)
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

    private suspend fun skalEndreOppfolgingskontorVedEndretGtKontor(kontorForGtResultat: KontorForGtSuccess, ident: IdentSomKanLagres): Boolean {
        val erSensitiv = kontorForGtResultat.sensitivitet().erSensitiv()
        val erUtland = kontorForGtResultat.gt() is GtLandForBrukerFunnet
        if (erSensitiv || erUtland) {
            return false
        }
        val oppfolgingskontor = hentAoKontor(ident)
        if (oppfolgingskontor?.kontorId == INGEN_GT_KONTOR_FALLBACK) {
            log.info("Bruker er tildelt fallback-kontor og har fått norsk GT, oppdaterer oppfølgingskontor")
            return true
        }
        return false
    }

    suspend fun handterEndringForAdressebeskyttelse(
        hendelse: AdressebeskyttelseEndret,
    ): HåndterPersondataEndretResultat {
        try {
            // Check oppfølgingsperiode status first
            val oppfolgingsStatus = hentGjeldendeOppfolgingsperiode(hendelse.ident)
            val oppfolgingsperiodeId = when (oppfolgingsStatus) {
                is NotUnderOppfolging -> {
                    log.info("Skipping adressebeskyttelse endring - no active oppfølgingsperiode")
                    return HåndterPersondataEndretIkkeUnderOppfølging
                }

                is OppfolgingperiodeOppslagFeil -> {
                    log.error("Error checking oppfølgingsperiode - ${oppfolgingsStatus.message}")
                    return HåndterPersondataEndretFail("Error checking oppfølgingsperiode: ${oppfolgingsStatus.message}")
                }

                is AktivOppfolgingsperiode -> oppfolgingsStatus.periodeId
            }

            val (skjermetResult, adressebeskyttelseResult) = coroutineScope {
                val skjermetDeferred = async { hentSkjerming(hendelse.ident) }
                val adressebeskyttelseDeferred = async { hentHarStrengtFortroligAdresse(hendelse.ident) }
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

            val gtKontorResultat = hentKontorForGt(hendelse.ident, harStrengtFortroligAdresse, erSkjermet)
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
                            tilordning = KontorTilordning(hendelse.ident, gtKontor, oppfolgingsperiodeId),
                            registrant = System(Systemnavn.PDL),
                        ).let { KontorEndringer(aoKontorEndret = it, gtKontorEndret = gtKontorEndring) }
                        HåndterPersondataEndretSuccess(kontorEndringer)
                    } else {
                        HåndterPersondataEndretSuccess(KontorEndringer(gtKontorEndret = gtKontorEndring))
                    }
                }

                is KontorForGtFeil -> {
                    val feilmelding =
                        "Kunne ikke håndtere endring i adressebeskyttelse pga feil ved henting av gt-kontor: ${gtKontorResultat.melding}"
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
    ): EndringISkjermingResult {
        return runCatching {
            val oppfolgingsperiodeId =
                when (val result = hentGjeldendeOppfolgingsperiode(endringISkjermingStatus.fnr)) {
                    is AktivOppfolgingsperiode -> result.periodeId
                    NotUnderOppfolging -> return EndringISkjermingBrukerIkkeUnderOppfølging
                    is OppfolgingperiodeOppslagFeil ->
                        return EndringISkjermingBehandlingFeilet(
                            Exception("Kunne ikke håndtere endring i skjerming pga feil ved henting av oppfolgingsstatus: ${result.message}")
                        )
                }

            val harStrengtFortroligAdresse =
                when (val result = hentHarStrengtFortroligAdresse(endringISkjermingStatus.fnr)) {
                    is HarStrengtFortroligAdresseIkkeFunnet ->
                        return EndringISkjermingBehandlingFeilet(
                            Exception(
                                "Kunne ikke hente adressebeskyttelse ved endring i skjermingstatus: ${result.message}"
                            )
                        )

                    is HarStrengtFortroligAdresseOppslagFeil ->
                        return EndringISkjermingBehandlingFeilet(
                            Exception(
                                "Kunne ikke hente adressebeskyttelse ved endring i skjermingstatus: ${result.message}"
                            )
                        )

                    is HarStrengtFortroligAdresseFunnet -> result.harStrengtFortroligAdresse
                }

            val gtKontorResultat = hentKontorForGt(
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
                    val aoKontorEndring = AOKontorEndretPgaSkjermingEndret(
                        kontorTilordning = KontorTilordning(
                            fnr = endringISkjermingStatus.fnr,
                            kontorId = gtKontor,
                            oppfolgingsperiodeId = oppfolgingsperiodeId,
                        ),
                        skjerming = endringISkjermingStatus.erSkjermet,
                        registrant = System(Systemnavn.SKJERMING),
                    )
                    val kontorEndringer = KontorEndringer(
                        aoKontorEndret = aoKontorEndring,
                        gtKontorEndret = gtKontorEndring,
                    )
                    EndringISkjermingSuccess(kontorEndringer)
                }

                is KontorForGtFeil -> {
                    val feilmelding =
                        "Kunne ikke håndtere endring i skjerming pga feil ved henting av gt-kontor: ${gtKontorResultat.melding}"
                    log.error(feilmelding)
                    EndringISkjermingBehandlingFeilet(Exception(feilmelding))
                }
            }
        }.getOrElse { error ->
            EndringISkjermingBehandlingFeilet(
                Exception("Uventet feil ved håndtering av endring i skjerming: ${error.message}", error)
            )
        }
    }

    fun getGTKontorOrFallback(gtKontorResultat: KontorForGtSuccess): KontorId {
        return when (gtKontorResultat) {
            // Enten default-kontor eller fallback-kontor (arbeidsfordeling/bestmatch)
            is KontorForGtFantKontor -> {
                if (gtKontorResultat.sensitivitet().strengtFortroligAdresse.value) {
                    VIKAFOSSEN
                } else {
                    gtKontorResultat.kontorId
                }
            }

            is KontorForGtFantIkkeKontor -> {
                if (gtKontorResultat.sensitivitet().strengtFortroligAdresse.value) {
                    VIKAFOSSEN
                } else if (gtKontorResultat.sensitivitet().skjermet.value) {
                    throw IllegalStateException(
                        "Skjermede brukere uten geografisk tilknytning eller med land som GT kan ikke tilordnes kontor: gt - ${gtKontorResultat.gtForBruker}"
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
        val worstCaseForsinkelse = Duration.ofMinutes(30)
        return (tidSidenBrukerBleRegistrert < worstCaseForsinkelse).also {
            log.info("Bruker ble registrert som arbeidssøker for tid siden: ${tidSidenBrukerBleRegistrert}")
        }
    }
}


fun KontorForGtSuccess.toGtKontorEndret(
    ident: IdentSomKanLagres,
    oppfolgingsperiodeId: OppfolgingsperiodeId
): GTKontorEndret? {
    if (this.erStrengtFortrolig()) {
        return GTKontorEndret.syncVedStartOppfolging(
            tilordning = KontorTilordning(
                fnr = ident,
                kontorId = VIKAFOSSEN,
                oppfolgingsperiodeId = oppfolgingsperiodeId
            ),
            this.gt()
        )
    }
    if (this.skjerming.value && this.gt() is GtLandForBrukerFunnet) {
        throw IllegalStateException(
            "Vi håndterer ikke skjermede brukere uten geografisk tilknytning"
        )
    }

    val (kontorId, gt) = when (this) {
        is KontorForGtFantDefaultKontor -> this.kontorId to this.gt()
        is KontorForGtNrFantFallbackKontorForManglendeGt -> this.kontorId to this.gt()
        is KontorForGtFantIkkeKontor -> null to this.gt()
        is KontorForGtFantKontorForArbeidsgiverAdresse -> INGEN_GT_KONTOR_FALLBACK to this.mangelfullGt as GtForBrukerSuccess
    }

    return when {
        kontorId != null -> GTKontorEndret.syncVedStartOppfolging(
            tilordning = KontorTilordning(
                fnr = ident,
                kontorId = kontorId,
                oppfolgingsperiodeId = oppfolgingsperiodeId
            ),
            gt
        )

        else -> null
    }
}