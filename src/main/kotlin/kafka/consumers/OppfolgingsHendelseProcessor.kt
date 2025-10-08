package kafka.consumers

import db.table.IdentMappingTable
import db.table.IdentMappingTable.internIdent
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
import no.nav.kafka.processor.*
import org.apache.kafka.streams.processor.api.Record
import org.jetbrains.exposed.sql.JoinType.INNER
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import services.*
import java.time.Instant
import java.util.*

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
        return this.copy(arenaKontorFraOppfolgingsbrukerTopic = forhåndslagretArenaKontor)
    }

    fun hentSisteArenaKontorFraOppfolgingsBrukerOgSlettHvisFunnet(ident: Ident): TidligArenaKontor? {
        return transaction {
            val alleIdenter = IdentMappingTable.alias("alleIdenter")
            val tidligArenaKontorOgIdent = IdentMappingTable
                .join(alleIdenter, INNER, onColumn = internIdent, otherColumn = alleIdenter[internIdent])
                .join(TidligArenaKontorTable, INNER, onColumn = alleIdenter[IdentMappingTable.id], otherColumn = TidligArenaKontorTable.id)
                .select(TidligArenaKontorTable.id, TidligArenaKontorTable.kontorId, TidligArenaKontorTable.sisteEndretDato)
                .where { IdentMappingTable.id eq ident.value }
                .map { row ->
                    TidligArenaKontor(
                        kontor = KontorId(row[TidligArenaKontorTable.kontorId]),
                        sistEndretDato = row[TidligArenaKontorTable.sisteEndretDato]
                    ) to row[TidligArenaKontorTable.id]
                }.firstOrNull()

            val identSomKontorErLagretPå = tidligArenaKontorOgIdent?.second
            val tidligArenaKontor = tidligArenaKontorOgIdent?.first

            identSomKontorErLagretPå?.let {
                TidligArenaKontorTable.deleteWhere { TidligArenaKontorTable.id eq identSomKontorErLagretPå.value }
            }

            tidligArenaKontor
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