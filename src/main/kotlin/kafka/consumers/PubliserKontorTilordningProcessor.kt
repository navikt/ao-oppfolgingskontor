package kafka.consumers

import domain.IdenterResult
import kafka.producers.OppfolgingEndretTilordningMelding
import kotlinx.coroutines.runBlocking
import no.nav.db.Ident
import no.nav.domain.OppfolgingsperiodeId
import no.nav.kafka.processor.Commit
import no.nav.kafka.processor.RecordProcessingResult
import no.nav.kafka.processor.Retry
import org.apache.kafka.common.serialization.Deserializer
import org.apache.kafka.common.serialization.Serde
import org.apache.kafka.common.serialization.Serializer
import org.apache.kafka.streams.processor.api.Record
import org.slf4j.LoggerFactory
import java.util.*

class PubliserKontorTilordningProcessor(
    val hentAlleIdenter: suspend (Ident) -> IdenterResult,
    val publiserKontorTilordning: suspend (kontorEndring: OppfolgingEndretTilordningMelding) -> Result<Unit>,
    val brukAoRuting: Boolean
) {
    val log = LoggerFactory.getLogger(this.javaClass)

    companion object {
        const val processorName = "PubliserKontorTilordningProcessor"
        val oppfolgingsperiodeIdSerde: Serde<OppfolgingsperiodeId> = object : Serde<OppfolgingsperiodeId> {
            override fun serializer(): Serializer<OppfolgingsperiodeId> =
                Serializer<OppfolgingsperiodeId> { topic, data -> data.toString().toByteArray() }
            override fun deserializer(): Deserializer<OppfolgingsperiodeId> =
                Deserializer<OppfolgingsperiodeId> { topic, data -> OppfolgingsperiodeId(UUID.fromString(data.decodeToString())) }
        }
        val kontortilordningSerde = jsonSerde<OppfolgingEndretTilordningMelding>()
    }

    fun process(
        record: Record<OppfolgingsperiodeId, OppfolgingEndretTilordningMelding>
    ): RecordProcessingResult<String, String> {
        if (brukAoRuting) {
            val result = runBlocking { publiserKontorTilordning(record.value()) }

            return when (result.isSuccess) {
                true -> Commit()
                false -> {
                    val message = "Kunne ikke publisere endring på ao-kontor til kafka topic: ${result.exceptionOrNull()?.message}"
                    log.error(message, result.exceptionOrNull())
                    Retry(message)
                }
            }
        } else {
            return Commit()
        }
    }
}