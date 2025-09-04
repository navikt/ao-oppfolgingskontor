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
import no.nav.domain.externalEvents.OppfolgingsperiodeStartet
import no.nav.kafka.processor.Commit
import no.nav.kafka.processor.RecordProcessingResult
import no.nav.kafka.processor.Retry
import org.apache.kafka.streams.processor.api.Record
import org.slf4j.LoggerFactory


class OppfolgingsHendelseProcessor() {
    val log = LoggerFactory.getLogger(javaClass)

    fun process(
        record: Record<String, String>
    ): RecordProcessingResult<Ident, OppfolgingsperiodeStartet> {
        val ident = Ident.of(record.key())
        return runCatching {
            val oppfolgingsperiodeDto = oppfolgingsHendelseJson
                .decodeFromString<OppfolgingsHendelseDto>(record.value())

            return when (oppfolgingsperiodeDto) {
                is OppfolgingStartetHendelseDto -> {
                    Commit<Ident, OppfolgingsperiodeStartet>()
                }
                is OppfolgingsAvsluttetHendelseDto -> {
                    Commit<Ident, OppfolgingsperiodeStartet>()
                }
            }
        }
            .getOrElse { error ->
                val hendelseType = "UKJENT"
                val feilmelding = "Kunne ikke behandle oppfolgingshenselse - ${hendelseType}: ${error.message}"
                log.error(feilmelding, error)
                Retry<Ident, OppfolgingsperiodeStartet>(feilmelding)
            }
    }

}
