package no.nav.services

import no.nav.domain.KontorId
import no.nav.domain.KontorTilordning
import no.nav.domain.events.AOKontorEndret
import no.nav.domain.events.AOKontorEndretPgaAdressebeskyttelseEndret
import no.nav.domain.events.AOKontorEndretPgaSkjermingEndret
import no.nav.domain.events.GTKontorPgaAdressebeskyttelseEndret
import no.nav.domain.events.GTKontorEndretPgaBostedsadresseEndret
import no.nav.domain.events.GTKontorEndretPgaSkjermingEndret
import no.nav.domain.events.OppfolgingsPeriodeStartetLokalKontorTilordning
import no.nav.domain.events.OppfolgingsperiodeStartetNoeTilordning
import no.nav.http.client.*
import no.nav.http.client.arbeidssogerregisteret.ProfileringsResultat
import no.nav.http.client.poaoTilgang.GTKontorFeil
import no.nav.http.client.poaoTilgang.GTKontorFunnet
import no.nav.http.client.poaoTilgang.GTKontorResultat
import no.nav.kafka.consumers.AddressebeskyttelseEndret
import no.nav.kafka.consumers.BostedsadresseEndret
import no.nav.kafka.consumers.EndringISkjermingResult
import no.nav.kafka.consumers.EndringISkjermingStatus
import no.nav.kafka.consumers.HåndterPersondataEndretFail
import no.nav.kafka.consumers.HåndterPersondataEndretResultat
import no.nav.kafka.consumers.HåndterPersondataEndretSuccess
import no.nav.person.pdl.leesah.adressebeskyttelse.Gradering
import org.slf4j.LoggerFactory

sealed class HentProfileringsResultat
data class ProfileringFunnet(val profilering: ProfileringsResultat) : HentProfileringsResultat()
data class ProfileringIkkeFunnet(val melding: String) : HentProfileringsResultat()
data class ProfileringsResultatFeil(val error: Throwable) : HentProfileringsResultat()

sealed class TilordningResultat
object TilordningSuccess : TilordningResultat()
data class TilordningFeil(val message: String) : TilordningResultat()


class AutomatiskKontorRutingService(
    private val gtKontorProvider: suspend (fnr: String) -> GTKontorResultat,
    private val aldersProvider: suspend (fnr: String) -> AlderResult,
    private val fnrProvider: suspend (aktorId: String) -> FnrResult,
    private val profileringProvider: suspend (fnr: String) -> HentProfileringsResultat,
) {
    val log = LoggerFactory.getLogger(this::class.java)

    suspend fun tilordneKontorAutomatisk(aktorId: String): TilordningResultat {
        try {
            val fnrResult = fnrProvider(aktorId)
            val fnr = when (fnrResult) {
                is FnrFunnet -> fnrResult.fnr
                is FnrIkkeFunnet -> return TilordningFeil("Fant ikke fnr: ${fnrResult.message}")
                is FnrOppslagFeil -> return TilordningFeil("Feil ved oppslag på fnr: ${fnrResult.message}")
            }

            val gtKontorResultat = gtKontorProvider(fnr)
            if (gtKontorResultat is GTKontorFeil) return TilordningFeil("Feil ved henting av gt-kontor: ${gtKontorResultat.melding}")

            val aldersResultat = aldersProvider(fnr)

            val kontorTilordning = hentTilordning(
                fnr,
                (gtKontorResultat as GTKontorFunnet).kontorId,
                if (aldersResultat is AlderFunnet) aldersResultat.alder else null,
                profileringProvider(fnr),
            )
            KontorTilordningService.tilordneKontor(kontorTilordning)
            return TilordningSuccess
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
        log.info("Profilering: $profilering")
        if (alder == null) throw IllegalArgumentException("Alder == null")

        if (profilering is ProfileringFunnet &&
            profilering.profilering == ProfileringsResultat.ANTATT_GODE_MULIGHETER &&
            alder in 31..59
        ) {
            return OppfolgingsperiodeStartetNoeTilordning(fnr)
        }

        return when {
            gtKontor == null -> OppfolgingsPeriodeStartetLokalKontorTilordning(KontorTilordning(fnr, KontorId("2990")))
            else -> OppfolgingsPeriodeStartetLokalKontorTilordning(KontorTilordning(fnr, gtKontor))
        }
    }

    suspend fun handterEndringForBostedsadresse(
        hendelse: BostedsadresseEndret,
    ): HåndterPersondataEndretResultat {
        try {
            val gtKontorResultat = gtKontorProvider(hendelse.fnr)
            return when (gtKontorResultat) {
                is GTKontorFunnet -> { KontorTilordningService.tilordneKontor(
                    GTKontorEndretPgaBostedsadresseEndret(
                        KontorTilordning(
                            hendelse.fnr,
                            gtKontorResultat.kontorId
                        )
                    ))
                    HåndterPersondataEndretSuccess
                }
                is GTKontorFeil -> {
                    val feilmelding = "Kunne ikke håndtere endring i bostedsadresse pga feil ved henting av gt-kontor: ${gtKontorResultat.melding}"
                    log.error(feilmelding) // TODO: Prøv igjen om det feiler
                    HåndterPersondataEndretFail(feilmelding)
                }
            }
        } catch (error: Throwable) {
            return HåndterPersondataEndretFail("Uventet feil ved håndtering av endring i bostedsadresse", error)
        }
    }

    suspend fun handterEndringForAdressebeskyttelse(
        hendelse: AddressebeskyttelseEndret,
    ): HåndterPersondataEndretResultat {
        try {
            val gtKontorResultat = gtKontorProvider(hendelse.fnr)
            return when (gtKontorResultat) {
                is GTKontorFunnet -> {
                    KontorTilordningService.tilordneKontor(
                        GTKontorPgaAdressebeskyttelseEndret(
                            KontorTilordning(
                                hendelse.fnr,
                                gtKontorResultat.kontorId
                            )
                        )
                    )
                    if (hendelse.erGradert()) {
                        KontorTilordningService.tilordneKontor(
                            AOKontorEndretPgaAdressebeskyttelseEndret(
                                KontorTilordning(
                                    hendelse.fnr,
                                    gtKontorResultat.kontorId
                                )
                            )
                        )
                    }
                    HåndterPersondataEndretSuccess
                }
                is GTKontorFeil -> {
                    val feilmelding = "Kunne ikke håndtere endring i adressebeskyttelse pga feil ved henting av gt-kontor: ${gtKontorResultat.melding}"
                    log.error(feilmelding) // TODO: Prøv igjen om det feiler
                    HåndterPersondataEndretFail(feilmelding)
                }
            }
        } catch (error: Throwable) {
            return HåndterPersondataEndretFail("Uventet feil ved håndtering av endring i adressebeskyttelse", error)
        }
    }

    suspend fun handterEndringISkjermingStatus(
        endringISkjermingStatus: EndringISkjermingStatus
    ): Result<EndringISkjermingResult> {
        return runCatching {
            val gtKontorResultat = gtKontorProvider(endringISkjermingStatus.fnr)
            return when (gtKontorResultat) {
                is GTKontorFunnet -> {
                    if (endringISkjermingStatus.erSkjermet) {
                        KontorTilordningService.tilordneKontor(
                            AOKontorEndretPgaSkjermingEndret(
                                KontorTilordning(
                                    endringISkjermingStatus.fnr,
                                    gtKontorResultat.kontorId
                                )
                            )
                        )
                        KontorTilordningService.tilordneKontor(
                            GTKontorEndretPgaSkjermingEndret(
                                KontorTilordning(
                                    endringISkjermingStatus.fnr,
                                    gtKontorResultat.kontorId
                                )
                            )
                        )
                        Result.success(EndringISkjermingResult.NY_ENHET)
                    } else {
                        Result.success(EndringISkjermingResult.IKKE_NY_ENHET)
                    }

                }
                is GTKontorFeil -> {
                    val feilmelding = "Kunne ikke håndtere endring i adressebeskyttelse pga feil ved henting av gt-kontor: ${gtKontorResultat.melding}"
                    log.error(feilmelding)
                    return Result.failure(Exception(feilmelding))
                }
            }
        }
    }
}

fun AddressebeskyttelseEndret.erGradert(): Boolean {
    return this.gradering == Gradering.STRENGT_FORTROLIG
            || this.gradering == Gradering.FORTROLIG
            || this.gradering == Gradering.STRENGT_FORTROLIG_UTLAND
}
