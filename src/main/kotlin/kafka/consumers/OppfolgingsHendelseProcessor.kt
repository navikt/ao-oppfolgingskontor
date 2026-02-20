package kafka.consumers

import kafka.consumers.oppfolgingsHendelser.OppfolgingStartBegrunnelse.ARBEIDSSOKER_REGISTRERING
import kafka.consumers.oppfolgingsHendelser.OppfolgingStartetHendelseDto
import kafka.consumers.oppfolgingsHendelser.OppfolgingsAvsluttetHendelseDto
import kafka.consumers.oppfolgingsHendelser.OppfolgingsHendelseDto
import kafka.consumers.oppfolgingsHendelser.oppfolgingsHendelseJson
import no.nav.db.Ident
import no.nav.db.IdentSomKanLagres
import no.nav.db.InternIdent
import no.nav.domain.KontorId
import no.nav.domain.OppfolgingsperiodeId
import no.nav.domain.externalEvents.KontorOverstyring
import no.nav.domain.externalEvents.OppfolgingsperiodeAvsluttet
import no.nav.domain.externalEvents.OppfolgingsperiodeEndret
import no.nav.domain.externalEvents.OppfolgingsperiodeStartet
import no.nav.kafka.processor.*
import org.apache.kafka.streams.processor.api.Record
import org.slf4j.LoggerFactory
import services.*
import java.time.Instant
import java.util.*

class OppfolgingsHendelseProcessor(
    val oppfolgingsPeriodeService: OppfolgingsperiodeService,
    val publiserTombstone: (internIdent: InternIdent) -> Result<Unit>,
) {
    val log = LoggerFactory.getLogger(javaClass)

    fun process(
        record: Record<String, String>
    ): RecordProcessingResult<Ident, OppfolgingsperiodeEndret> {
        var hendelseType = "<Ukjent hendelsetype>"
        val ident = Ident.validateOrThrow(record.key(), Ident.HistoriskStatus.UKJENT)
        return runCatching {
            val oppfolgingsperiodeEvent = oppfolgingsHendelseJson
                .decodeFromString<OppfolgingsHendelseDto>(record.value())

            return when (oppfolgingsperiodeEvent) {
                is OppfolgingStartetHendelseDto -> {
                    hendelseType = oppfolgingsperiodeEvent.hendelseType.name
                    val oppfolgingStartetInternalEvent = oppfolgingsperiodeEvent.toDomainObject()
                    val periodeResult = oppfolgingsPeriodeService.handterPeriodeStartet(oppfolgingStartetInternalEvent)
                    return when (periodeResult) {
                        HaddeNyerePeriodePåIdent,
                        HarSlettetPeriode,
                        HaddePeriodeMedTilordningAllerede -> Skip()

                        HaddePeriodeAlleredeMenManglerTilordning,
                        OppfølgingsperiodeLagret -> {
                            Forward(
                                Record(
                                    ident,
                                    oppfolgingStartetInternalEvent,
                                    Instant.now().toEpochMilli()
                                ), null
                            )
                        }
                    }
                }

                is OppfolgingsAvsluttetHendelseDto -> {
                    hendelseType = oppfolgingsperiodeEvent.hendelseType.name
                    val oppfolgingsPeriodeAvsluttet = oppfolgingsperiodeEvent.toDomainObject()
                    oppfolgingsPeriodeService.handterPeriodeAvsluttet(oppfolgingsPeriodeAvsluttet)
                        .toRecordResult(oppfolgingsPeriodeAvsluttet)
                }
            }
        }
            .getOrElse { error ->
                val feilmelding = "Kunne ikke behandle oppfolgingshendelse - ${hendelseType}: ${error.message}"
                log.error(feilmelding, error)
                Retry<Ident, OppfolgingsperiodeEndret>(feilmelding)
            }
    }

    fun HandterPeriodeAvsluttetResultat.toRecordResult(event: OppfolgingsperiodeAvsluttet): RecordProcessingResult<Ident, OppfolgingsperiodeEndret> {
        return when (this) {
            IngenPeriodeAvsluttet -> Skip()
            is GammelPeriodeAvsluttet -> {
                val result = publiserTombstone(this.internIdent)
                result.fold(
                    onSuccess = { Commit() },
                    onFailure = { Retry("Feilet å publisere tombstone på kafka: ${it.message}") }
                )
            }
            is InnkommendePeriodeAvsluttet -> {
                val result = publiserTombstone(this.internIdent)
                result.fold(
                    onSuccess = { Commit() },
                    onFailure = { Retry("Feilet å publisere tombstone på kafka: ${it.message}") }
                )
            }
        }
    }
}

fun OppfolgingStartetHendelseDto.toDomainObject() = OppfolgingsperiodeStartet(
    fnr = Ident.validateOrThrow(this.fnr, Ident.HistoriskStatus.UKJENT) as? IdentSomKanLagres
        ?: throw IllegalStateException("Ident i oppfolgingshendelse-topic kan ikke være aktorId"),
    startDato = this.startetTidspunkt,
    periodeId = OppfolgingsperiodeId(UUID.fromString(this.oppfolgingsPeriodeId)),
    erArbeidssøkerRegistrering = startetBegrunnelse == ARBEIDSSOKER_REGISTRERING,
    foretrukketArbeidsoppfolgingskontor = this.foretrukketArbeidsoppfolgingskontor?.let { KontorId(it) },
    kontorOverstyring = this.foretrukketArbeidsoppfolgingskontor?.let {
        KontorOverstyring(
            this.startetAv,
            this.startetAvType,
            KontorId(it),
        )
    }
)

fun OppfolgingsAvsluttetHendelseDto.toDomainObject() = OppfolgingsperiodeAvsluttet(
    Ident.validateOrThrow(this.fnr, Ident.HistoriskStatus.UKJENT) as? IdentSomKanLagres
        ?: throw IllegalStateException("Ident i oppfolgingshendelse-topic kan ikke være aktorId"),
    this.startetTidspunkt,
    OppfolgingsperiodeId(UUID.fromString(this.oppfolgingsPeriodeId))
)