package kafka.consumers

import db.entity.TidligArenaKontorEntity
import db.table.TidligArenaKontorTable
import kafka.consumers.oppfolgingsHendelser.OppfolgingStartBegrunnelse.ARBEIDSSOKER_REGISTRERING
import kafka.consumers.oppfolgingsHendelser.OppfolgingStartetHendelseDto
import kafka.consumers.oppfolgingsHendelser.OppfolgingsAvsluttetHendelseDto
import kafka.consumers.oppfolgingsHendelser.OppfolgingsHendelseDto
import kafka.consumers.oppfolgingsHendelser.oppfolgingsHendelseJson
import no.nav.db.Ident
import no.nav.db.IdentSomKanLagres
import no.nav.domain.KontorId
import no.nav.domain.OppfolgingsperiodeId
import no.nav.domain.externalEvents.OppfolgingsperiodeAvsluttet
import no.nav.domain.externalEvents.OppfolgingsperiodeStartet
import no.nav.domain.externalEvents.TidligArenaKontor
import no.nav.kafka.processor.Commit
import no.nav.kafka.processor.Forward
import no.nav.kafka.processor.RecordProcessingResult
import no.nav.kafka.processor.Retry
import no.nav.kafka.processor.Skip
import org.apache.kafka.streams.processor.api.Record
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import services.GammelPeriodeAvsluttet
import services.HaddeNyerePeriodePåIdent
import services.HaddePeriodeAlleredeMenManglerTilordning
import services.HaddePeriodeMedTilordningAllerede
import services.HandterPeriodeAvsluttetResultat
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
        val ident = Ident.of(record.key(), Ident.HistoriskStatus.UKJENT)
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
                                    oppfolgingStartetInternalEvent.enrichWithTidligArenaKontor(),
                                    Instant.now().toEpochMilli()
                                ), null
                            )
                        }
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

    fun OppfolgingsperiodeStartet.enrichWithTidligArenaKontor(): OppfolgingsperiodeStartet {
        val forhåndslagretArenaKontor = hentSisteArenaKontorFraOppfolgingsBrukerOgSlettHvisFunnet(this.fnr)
        return this.copy(
            arenaKontorFraOppfolgingsbrukerTopic = forhåndslagretArenaKontor?.let {
                TidligArenaKontor(
                    it.sistEndretDato,
                    KontorId(it.kontorId)
                )
            }
        )
    }

    fun hentSisteArenaKontorFraOppfolgingsBrukerOgSlettHvisFunnet(ident: Ident): TidligArenaKontorEntity? {
        return transaction {
            TidligArenaKontorEntity.findById(ident.value)
                ?.also {
                    TidligArenaKontorTable.deleteWhere {
                        id eq ident.value
                    }
                }
        }
    }
}

fun OppfolgingStartetHendelseDto.toDomainObject() = OppfolgingsperiodeStartet(
    fnr = Ident.of(this.fnr, Ident.HistoriskStatus.UKJENT) as? IdentSomKanLagres
        ?: throw IllegalStateException("Ident i oppfolgingshendelse-topic kan ikke være aktorId"),
    startDato = this.startetTidspunkt,
    periodeId = OppfolgingsperiodeId(UUID.fromString(this.oppfolgingsPeriodeId)),
    arenaKontorFraOppfolgingsbrukerTopic = null,
    erArbeidssøkerRegistrering = startetBegrunnelse == ARBEIDSSOKER_REGISTRERING
)
fun OppfolgingsAvsluttetHendelseDto.toDomainObject() = OppfolgingsperiodeAvsluttet(
    Ident.of(this.fnr, Ident.HistoriskStatus.UKJENT) as? IdentSomKanLagres
        ?: throw IllegalStateException("Ident i oppfolgingshendelse-topic kan ikke være aktorId"),
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