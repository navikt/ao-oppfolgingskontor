package kafka.consumers

import kafka.consumers.oppfolgingsHendelser.OppfolgingStartetHendelseDto
import kafka.consumers.oppfolgingsHendelser.OppfolgingsAvsluttetHendelseDto
import kafka.consumers.oppfolgingsHendelser.OppfolgingsHendelseDto
import kafka.consumers.oppfolgingsHendelser.oppfolgingsHendelseJson
import no.nav.db.Ident
import no.nav.domain.KontorId
import no.nav.domain.OppfolgingsperiodeId
import no.nav.domain.externalEvents.OppfolgingsperiodeAvsluttet
import no.nav.domain.externalEvents.OppfolgingsperiodeStartet
import no.nav.kafka.processor.Commit
import no.nav.kafka.processor.Forward
import no.nav.kafka.processor.RecordProcessingResult
import no.nav.kafka.processor.Retry
import no.nav.kafka.processor.Skip
import no.nav.services.AutomatiskKontorRutingService
import org.apache.kafka.streams.processor.api.Record
import org.slf4j.LoggerFactory
import services.GammelPeriodeAvsluttet
import services.HaddeNyerePeriodePåIdent
import services.HaddePeriodeAllerede
import services.HandterPeriodeAvsluttetResultat
import services.HandterPeriodeStartetResultat
import services.HarSlettetPeriode
import services.IngenPeriodeAvsluttet
import services.InnkommendePeriodeAvsluttet
import services.OppfolgingsperiodeService
import services.OppfølgingsperiodeLagret
import java.time.Instant
import java.util.UUID

class OppfolgingsHendelseProcessor(
    val oppfolgingsPeriodeService: OppfolgingsperiodeService,
) {
    val log = LoggerFactory.getLogger(javaClass)

    fun process(
        record: Record<String, String>
    ): RecordProcessingResult<Ident, OppfolgingsperiodeStartet> {
        var hendelseType = "<Ukjent hendelsetype>"
        return runCatching {
            val oppfolgingsperiodeEvent = oppfolgingsHendelseJson
                .decodeFromString<OppfolgingsHendelseDto>(record.value())

            return when (oppfolgingsperiodeEvent) {
                is OppfolgingStartetHendelseDto -> {
                    hendelseType = oppfolgingsperiodeEvent.hendelseType.name
                    val oppfolgingStartetInternalEvent = oppfolgingsperiodeEvent.toDomainObject()
                    val periodeResult = oppfolgingsPeriodeService.handterPeriodeStartet(oppfolgingStartetInternalEvent)
                    return when (periodeResult) {
                        HaddeNyerePeriodePåIdent, HaddePeriodeAllerede, HarSlettetPeriode -> Skip()
                        OppfølgingsperiodeLagret -> Forward(
                            Record(
                                Ident.of(record.key()),
                                oppfolgingStartetInternalEvent,
                            Instant.now().toEpochMilli()
                            ), null
                        )
                    }
                }
                is OppfolgingsAvsluttetHendelseDto -> {
                    hendelseType = oppfolgingsperiodeEvent.hendelseType.name
                    oppfolgingsPeriodeService.handterPeriodeAvsluttet(oppfolgingsperiodeEvent.toDomainObject())
                        .toRecordResult()
                }
            }
        }
            .getOrElse { error ->
                val feilmelding = "Kunne ikke behandle oppfolgingshendelse - ${hendelseType}: ${error.message}"
                log.error(feilmelding, error)
                Retry<Ident, OppfolgingsperiodeStartet>(feilmelding)
            }
    }
}

fun OppfolgingStartetHendelseDto.toDomainObject() = OppfolgingsperiodeStartet(
    Ident.of(this.fnr),
    this.startetTidspunkt,
    OppfolgingsperiodeId(UUID.fromString(this.oppfolgingsPeriodeId)),
    this.arenaKontor?.let { KontorId(it) }
)
fun OppfolgingsAvsluttetHendelseDto.toDomainObject() = OppfolgingsperiodeAvsluttet(
    Ident.of(this.fnr),
    this.startetTidspunkt,
    OppfolgingsperiodeId(UUID.fromString(this.oppfolgingsPeriodeId))
)
fun HandterPeriodeAvsluttetResultat.toRecordResult(): RecordProcessingResult<Ident, OppfolgingsperiodeStartet> {
    return when (this) {
        GammelPeriodeAvsluttet -> Commit()
        IngenPeriodeAvsluttet -> Skip()
        InnkommendePeriodeAvsluttet -> Commit()
    }
}