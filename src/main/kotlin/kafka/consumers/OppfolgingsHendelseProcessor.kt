package kafka.consumers

import kafka.consumers.oppfolgingsHendelser.OppfolgingStartetHendelseDto
import kafka.consumers.oppfolgingsHendelser.OppfolgingsAvsluttetHendelseDto
import kafka.consumers.oppfolgingsHendelser.OppfolgingsHendelseDto
import kafka.consumers.oppfolgingsHendelser.oppfolgingsHendelseJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import no.nav.db.Ident
import no.nav.domain.OppfolgingsperiodeId
import no.nav.domain.externalEvents.OppfolgingsperiodeAvsluttet
import no.nav.domain.externalEvents.OppfolgingsperiodeStartet
import no.nav.kafka.processor.Commit
import no.nav.kafka.processor.RecordProcessingResult
import no.nav.kafka.processor.Retry
import org.apache.kafka.streams.processor.api.Record
import org.slf4j.LoggerFactory
import services.OppfolgingsperiodeService
import java.util.UUID

class OppfolgingsHendelseProcessor(
    val oppfolgingsPeriodeService: OppfolgingsperiodeService
) {
    val log = LoggerFactory.getLogger(javaClass)

    fun process(
        record: Record<String, String>
    ): RecordProcessingResult<Ident, OppfolgingsperiodeStartet> {
        var hendelseType = "Ukjent"
        return runCatching {
            val oppfolgingsperiodeEvent = oppfolgingsHendelseJson
                .decodeFromString<OppfolgingsHendelseDto>(record.value())

            return when (oppfolgingsperiodeEvent) {
                is OppfolgingStartetHendelseDto -> {
                    hendelseType = oppfolgingsperiodeEvent.hendelseType.name
                    oppfolgingsPeriodeService.handterPeriodeStartet(oppfolgingsperiodeEvent.toDomainObject())
                    Commit<Ident, OppfolgingsperiodeStartet>()
                }
                is OppfolgingsAvsluttetHendelseDto -> {
                    hendelseType = oppfolgingsperiodeEvent.hendelseType.name
                    oppfolgingsPeriodeService.handterPeriodeAvsluttet(oppfolgingsperiodeEvent.toDomainObject())
                    Commit<Ident, OppfolgingsperiodeStartet>()
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
    OppfolgingsperiodeId(UUID.fromString(this.oppfolgingsPeriodeId))
)
fun OppfolgingsAvsluttetHendelseDto.toDomainObject() = OppfolgingsperiodeAvsluttet(
    Ident.of(this.fnr),
    this.startetTidspunkt,
    OppfolgingsperiodeId(UUID.fromString(this.oppfolgingsPeriodeId))
)
