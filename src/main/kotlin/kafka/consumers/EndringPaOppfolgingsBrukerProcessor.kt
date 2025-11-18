package no.nav.kafka.consumers

import domain.ArenaKontorUtvidet
import kafka.producers.OppfolgingEndretTilordningMelding
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import no.nav.PUBLISER_ARENA_KONTOR
import no.nav.db.Ident
import no.nav.db.IdentSomKanLagres
import no.nav.domain.KontorEndringsType
import no.nav.domain.KontorId
import no.nav.domain.KontorTilordning
import no.nav.domain.OppfolgingsperiodeId
import no.nav.domain.events.ArenaKontorFraOppfolgingsbrukerVedOppfolgingStartMedEtterslep
import no.nav.domain.events.EndringPaaOppfolgingsBrukerFraArena
import no.nav.domain.events.KontorEndretEvent
import no.nav.kafka.processor.Commit
import no.nav.kafka.processor.RecordProcessingResult
import no.nav.kafka.processor.Retry
import no.nav.kafka.processor.Skip
import no.nav.services.AktivOppfolgingsperiode
import no.nav.services.NotUnderOppfolging
import no.nav.services.OppfolgingperiodeOppslagFeil
import no.nav.services.OppfolgingsperiodeOppslagResult
import org.apache.kafka.streams.processor.api.Record
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
import java.time.ZoneOffset

class EndringPaOppfolgingsBrukerProcessor(
    val oppfolgingsperiodeProvider: suspend (IdentSomKanLagres) -> OppfolgingsperiodeOppslagResult,
    val arenaKontorProvider: suspend (IdentSomKanLagres) -> ArenaKontorUtvidet?,
    val lagreKontorTilordninger: (KontorEndretEvent) -> Unit,
    val publiserKontorTilordning: suspend (kontorEndring: OppfolgingEndretTilordningMelding) -> Result<Unit>,
    val publiserArenaKontor: Boolean = PUBLISER_ARENA_KONTOR
) {
    val log = LoggerFactory.getLogger(EndringPaOppfolgingsBrukerProcessor::class.java)

    val json = Json { ignoreUnknownKeys = true }

    fun handleResult(result: EndringPaaOppfolgingsBrukerResult): RecordProcessingResult<String, String> {
        return when (result) {
            is BeforeCutoff -> {
                log.info("Endring på oppfolgingsbruker var fra før cutoff, hopper over")
                Skip()
            }
            is Feil -> {
                log.error("Klarte ikke behandle melding om endring på oppfølgingsbruker: ${result.retry.reason}")
                result.retry
            }
            is HaddeNyereEndring -> {
                log.warn("Sist endret kontor er eldre enn endring på oppfølgingsbruker")
                Skip()
            }
            is MeldingManglerEnhet -> {
                log.warn("Mottok endring på oppfølgingsbruker uten gyldig kontorId")
                Skip()
            }
            is IngenEndring -> {
                log.info("Kontor har ikke blitt endret, hopper over melding om endring på oppfølgingsbruker")
                Skip()
            }
            is IkkeUnderOppfølging -> {
                log.info("Bruker er ikke under oppfølging (ennå), hopper over melding om endring på oppfølgingsbruker")
                Skip()
            }
            is SkalLagre -> {
                val kontorTilordning = KontorTilordning(
                    fnr = result.fnr,
                    kontorId = KontorId(result.oppfolgingsenhet),
                    result.oppfolgingsperiodeId
                )
                lagreKontorTilordninger(
                    if (result.erFørsteArenaKontorIOppfolgingsperiode) {
                        ArenaKontorFraOppfolgingsbrukerVedOppfolgingStartMedEtterslep(
                            kontorTilordning = kontorTilordning,
                            sistEndretIArena = result.endretTidspunkt,
                        )
                    } else {
                        EndringPaaOppfolgingsBrukerFraArena(
                            kontorTilordning = kontorTilordning,
                            sistEndretIArena = result.endretTidspunkt,
                        )
                    }
                )
                if (publiserArenaKontor) {
                    val kontorEndringstype = if (result.erFørsteArenaKontorIOppfolgingsperiode) {
                        KontorEndringsType.ArenaKontorVedOppfolgingStartMedEtterslep
                    } else {
                        KontorEndringsType.EndretIArena
                    }
                    runBlocking {
                        publiserKontorTilordning(
                            OppfolgingEndretTilordningMelding(
                                kontorId = kontorTilordning.kontorId.id,
                                oppfolgingsperiodeId = kontorTilordning.oppfolgingsperiodeId.value.toString(),
                                ident =  kontorTilordning.fnr.value,
                                kontorEndringsType = kontorEndringstype
                            )
                        )
                    }
                }
                Commit()
            }
        }
    }

    fun process(record: Record<String, String>): RecordProcessingResult<String, String> {
        try {
            val result: EndringPaaOppfolgingsBrukerResult = internalProcess(record)
            return handleResult(result)
        } catch (e: Throwable) {
            val message = "Uhåndtert feil ved behandling av endring på oppfolgingsbruker fra Arena: ${e.message}"
            log.error(message, e)
            return handleResult(Feil(Retry(message)))
        }
    }

    fun internalProcess(record: Record<String, String>): EndringPaaOppfolgingsBrukerResult {
        val ident = Ident.validateOrThrow(record.key(), Ident.HistoriskStatus.UKJENT) as IdentSomKanLagres
        val endringPaOppfolgingsBruker = json.decodeFromString<EndringPaOppfolgingsBrukerDto>(record.value())
        val oppfolgingsenhet = endringPaOppfolgingsBruker.oppfolgingsenhet
        val endretTidspunktInnkommendeMelding = endringPaOppfolgingsBruker.sistEndretDato.convertToOffsetDatetime()

        val sistLagretArenaKontor by lazy { runBlocking { arenaKontorProvider(ident) } }
        val oppfolgingperiode by lazy { runBlocking { oppfolgingsperiodeProvider(ident) } }

        fun harNyereLagretEndring(): Boolean {
            val sistEndretDatoArena = sistLagretArenaKontor?. sistEndretDatoArena ?: return false
            return sistEndretDatoArena > endretTidspunktInnkommendeMelding
        }

        return when {
            oppfolgingsenhet.isNullOrBlank() -> MeldingManglerEnhet()
            endretTidspunktInnkommendeMelding.isBefore(ENDRING_PA_OPPFOLGINGSBRUKER_CUTOFF) -> BeforeCutoff()
            harNyereLagretEndring() -> HaddeNyereEndring()
            else -> {
                when (val periode = oppfolgingperiode) {
                    is AktivOppfolgingsperiode -> {
                        return when (val endringsType = harKontorBlittEndret(sistLagretArenaKontor, oppfolgingsenhet, periode.periodeId)) {
                            ArenaKontorEndringsType.IKKE_ENDRET_KONTOR -> IngenEndring()
                            ArenaKontorEndringsType.ENDRET_I_PERIODE,
                            ArenaKontorEndringsType.FØRSTE_KONTOR_I_PERIODE,
                            ArenaKontorEndringsType.FØRSTE_KONTOR_PÅ_BRUKER -> SkalLagre(
                                oppfolgingsenhet,
                                endretTidspunktInnkommendeMelding,
                                ident,
                                periode.periodeId,
                                erFørsteArenaKontorIOppfolgingsperiode = endringsType.erFørsteArenaKontorIOppfolgingsperiode()
                            )
                        }
                    }
                    is NotUnderOppfolging -> IkkeUnderOppfølging()
                    is OppfolgingperiodeOppslagFeil -> Feil(
                        Retry("Feil ved oppslag på oppfølgingsperiode: ${periode.message}"),
                    )
                }
            }
        }
    }
}

fun harKontorBlittEndret(arenaKontorUtvidet: ArenaKontorUtvidet?, oppfolgingsEnhetFraTopic: String, oppfolgingsperiodeId: OppfolgingsperiodeId): ArenaKontorEndringsType {
    if (arenaKontorUtvidet == null) return ArenaKontorEndringsType.FØRSTE_KONTOR_PÅ_BRUKER

    val endringErINyPeriode = arenaKontorUtvidet.oppfolgingsperiodeId?.value != oppfolgingsperiodeId.value

    return if (arenaKontorUtvidet.kontorId.id != oppfolgingsEnhetFraTopic) {
        when (endringErINyPeriode) {
            true -> ArenaKontorEndringsType.FØRSTE_KONTOR_I_PERIODE
            false ->  ArenaKontorEndringsType.ENDRET_I_PERIODE
        }
    } else {
        when (endringErINyPeriode) {
            /* Setter samme kontoret på nytt med ny oppfølgingsperiode */
            true -> ArenaKontorEndringsType.FØRSTE_KONTOR_I_PERIODE
            false ->  ArenaKontorEndringsType.IKKE_ENDRET_KONTOR
        }
    }
}

sealed class EndringPaaOppfolgingsBrukerResult
class BeforeCutoff : EndringPaaOppfolgingsBrukerResult()
class HaddeNyereEndring : EndringPaaOppfolgingsBrukerResult()
class IkkeUnderOppfølging : EndringPaaOppfolgingsBrukerResult()
class MeldingManglerEnhet : EndringPaaOppfolgingsBrukerResult()
class SkalLagre(
    val oppfolgingsenhet: String,
    val endretTidspunkt: OffsetDateTime,
    val fnr: IdentSomKanLagres,
    val oppfolgingsperiodeId: OppfolgingsperiodeId,
    val erFørsteArenaKontorIOppfolgingsperiode: Boolean,
) : EndringPaaOppfolgingsBrukerResult()

class IngenEndring : EndringPaaOppfolgingsBrukerResult()
class Feil(
    val retry: Retry<String, String>
) : EndringPaaOppfolgingsBrukerResult()

/*
* Endringer fra topic før cutoff har blitt eller er migrert manuelt. Vi tar bare imot endringer fra etter cutoff
* */
val ENDRING_PA_OPPFOLGINGSBRUKER_CUTOFF = OffsetDateTime.of(
    2025,
    8,
    13,
    0,
    0,
    0,
    0,
    ZoneOffset.UTC
)
// ""sistEndretDato":string"2025-04-10T13:01:14+02:00"

fun String.convertToOffsetDatetime(): OffsetDateTime {
    return OffsetDateTime.parse(this)
}

enum class FormidlingsGruppe {
    ISERV,
    ARBS,
    IARBS
}

enum class Kvalifiseringsgruppe {
    BATT,   // Spesielt tilpasset innsats:	                Personen har nedsatt arbeidsevne og har et identifisert behov for kvalifisering og/eller tilrettelegging.  Aktivitetsplan skal utformes.
    BFORM, // Situasjonsbestemt innsats:	                    Personen har moderat bistandsbehov
    BKART, // Behov for arbeidsevnevurdering:	            Personen har behov for arbeidsevnevurdering
    IKVAL, // Standardinnsats:	                            Personen har behov for ordinær bistand
    IVURD, // Ikke vurdert:	                                Ikke vurdert
    KAP11, // Rettigheter etter Ftrl. Kapittel 11:	        Rettigheter etter Ftrl. Kapittel 11
    OPPFI, // Helserelatert arbeidsrettet oppfølging i NAV:	Helserelatert arbeidsrettet oppfølging i NAV
    VARIG, // Varig tilpasset innsats:	                    Personen har varig nedsatt arbeidsevne
    VURDI, // Sykmeldt, oppfølging på arbeidsplassen:	    Sykmeldt, oppfølging på arbeidsplassen
    VURDU; // Sykmeldt uten arbeidsgiver:	                Sykmeldt uten arbeidsgiver
}

@Serializable
data class EndringPaOppfolgingsBrukerDto(
    val oppfolgingsenhet: String?,
    val sistEndretDato: String,
    val formidlingsgruppe: FormidlingsGruppe,
    val kvalifiseringsgruppe: Kvalifiseringsgruppe
)

enum class ArenaKontorEndringsType {
    IKKE_ENDRET_KONTOR,
    FØRSTE_KONTOR_I_PERIODE,
    FØRSTE_KONTOR_PÅ_BRUKER,
    ENDRET_I_PERIODE;

    fun erFørsteArenaKontorIOppfolgingsperiode(): Boolean {
        return this == FØRSTE_KONTOR_PÅ_BRUKER || this == FØRSTE_KONTOR_I_PERIODE
    }
}