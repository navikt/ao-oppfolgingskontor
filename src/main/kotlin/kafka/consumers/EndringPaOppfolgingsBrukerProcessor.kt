package no.nav.kafka.consumers

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import no.nav.db.Fnr
import no.nav.db.entity.ArenaKontorEntity
import no.nav.domain.KontorId
import no.nav.domain.KontorTilordning
import no.nav.domain.OppfolgingsperiodeId
import no.nav.domain.events.EndringPaaOppfolgingsBrukerFraArena
import no.nav.kafka.processor.Commit
import no.nav.kafka.processor.RecordProcessingResult
import no.nav.kafka.processor.Retry
import no.nav.kafka.processor.Skip
import no.nav.services.KontorTilordningService
import org.apache.kafka.streams.processor.api.Record
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
import java.time.ZoneOffset

class EndringPaOppfolgingsBrukerProcessor(
    val sistLagretArenaKontorProvider: (Fnr) -> ArenaKontorEntity?,
//    val oppfolgingsperiodeProvider: suspend (IdentResult) -> OppfolgingsperiodeOppslagResult,
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
            is IkkeUnderOppfolging -> {
                log.info("Bruker er ikke under oppfølging, hopper over melding om endring på oppfølgingsbruker")
                Skip()
            }
            is IngenEndring -> {
                log.info("Kontor har ikke blitt endret, hopper over melding om endring på oppfølgingsbruker")
                Skip()
            }
            is SkalLagre -> {
                KontorTilordningService.tilordneKontor(
                    EndringPaaOppfolgingsBrukerFraArena(
                        tilordning = KontorTilordning(
                            fnr = result.fnr,
                            kontorId = KontorId(result.oppfolgingsenhet),
                            result.oppfolgingsperiodeId
                        ),
                        sistEndretDatoArena = result.endretTidspunkt,
                    )
                )
                Commit()
            }
        }
    }

    fun process(record: Record<String, String>): RecordProcessingResult<String, String> {
        val result = internalProcess(record)
        return handleResult(result)
    }

    fun internalProcess(record: Record<String, String>): EndringPaaOppfolgingsBrukerResult {
        val fnr = Fnr(record.key())
        val endringPaOppfolgingsBruker = json.decodeFromString<EndringPaOppfolgingsBrukerDto>(record.value())
        val oppfolgingsenhet = endringPaOppfolgingsBruker.oppfolgingsenhet
        val endretTidspunktInnkommendeMelding = endringPaOppfolgingsBruker.sistEndretDato.convertToOffsetDatetime()

        val sistLagretArenaKontor by lazy { sistLagretArenaKontorProvider(fnr) }

        fun harNyereLagretEndring(): Boolean {
            val sistEndretDatoArena = sistLagretArenaKontor?.sistEndretDatoArena
            return (sistEndretDatoArena != null && sistEndretDatoArena > endretTidspunktInnkommendeMelding)
        }

        fun harKontorBlittEndret(): Boolean {
            val sistLagretKontorId = sistLagretArenaKontor?.kontorId
            return when (sistLagretKontorId) {
                null -> true
                else -> sistLagretKontorId != oppfolgingsenhet
            }
        }

        return when {
            oppfolgingsenhet.isNullOrBlank() -> MeldingManglerEnhet()
            endretTidspunktInnkommendeMelding.isBefore(ENDRING_PA_OPPFOLGINGSBRUKER_CUTOFF) -> BeforeCutoff()
            harNyereLagretEndring() -> HaddeNyereEndring()
            else -> {

                return when (harKontorBlittEndret()) {
                    true -> SkalLagre(
                        oppfolgingsenhet,
                        endretTidspunktInnkommendeMelding,
                        fnr,
                        oppfolgingperiode.periodeId
                    )
                    false -> IngenEndring()
                }

//                when (val oppfolgingperiode = runBlocking { oppfolgingsperiodeProvider(IdentFunnet(fnr)) }) {
//                    is AktivOppfolgingsperiode -> {
//                        return when (harKontorBlittEndret()) {
//                            true -> SkalLagre(
//                                oppfolgingsenhet,
//                                endretTidspunktInnkommendeMelding,
//                                fnr,
//                                oppfolgingperiode.periodeId
//                            )
//                            false -> IngenEndring()
//                        }
//                    }
//                    NotUnderOppfolging -> IkkeUnderOppfolging()
//                    is OppfolgingperiodeOppslagFeil -> Feil(
//                        Retry("Klarte ikke behandle melding om endring på oppfølgingsbruker, feil ved oppslag på oppfølgingsperiode: ${oppfolgingperiode.message}"),
//                    )
//                }
            }
        }
    }
}

sealed class EndringPaaOppfolgingsBrukerResult
class BeforeCutoff : EndringPaaOppfolgingsBrukerResult()
class HaddeNyereEndring : EndringPaaOppfolgingsBrukerResult()
class IkkeUnderOppfolging : EndringPaaOppfolgingsBrukerResult()
class MeldingManglerEnhet : EndringPaaOppfolgingsBrukerResult()
class SkalLagre(
    val oppfolgingsenhet: String,
    val endretTidspunkt: OffsetDateTime,
    val fnr: Fnr,
    val oppfolgingsperiodeId: OppfolgingsperiodeId
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

@Serializable
data class EndringPaOppfolgingsBrukerDto(
    val oppfolgingsenhet: String?,
    val sistEndretDato: String
)