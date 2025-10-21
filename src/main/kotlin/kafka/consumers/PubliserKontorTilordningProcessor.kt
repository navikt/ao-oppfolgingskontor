package kafka.consumers

import kafka.producers.KontorTilordningMelding
import kotlinx.coroutines.runBlocking
import no.nav.db.Ident
import no.nav.http.client.IdenterFunnet
import no.nav.http.client.IdenterIkkeFunnet
import no.nav.http.client.IdenterOppslagFeil
import no.nav.kafka.consumers.KontortilordningsProcessor
import no.nav.kafka.processor.Commit
import no.nav.kafka.processor.RecordProcessingResult
import no.nav.kafka.processor.Retry
import org.apache.kafka.common.serialization.Serde
import org.apache.kafka.streams.processor.api.Record
import org.slf4j.LoggerFactory
import services.IdentService

class PubliserKontorTilordningProcessor(
    val identService: IdentService,
    val publiserKontorTilordning: suspend (kontorEndring: KontorTilordningMelding) -> Result<Unit>,
    val publiserTombstone: suspend(identer: IdenterFunnet) -> Result<Unit>,
) {
    val log = LoggerFactory.getLogger(this.javaClass)

    companion object {
        const val processorName = "PubliserKontorTilordningProcessor"
        val identSerde: Serde<Ident> = KontortilordningsProcessor.identSerde
        val kontortilordningSerde = jsonSerde<KontorTilordningMelding>()
    }

    fun process(
        record: Record<Ident, KontorTilordningMelding>
    ): RecordProcessingResult<String, String> {
        val result = runBlocking {
            if(record.value() == null) {
                val identerResult = identService.hentAlleIdenter(record.key())
                when(identerResult) {
                    is IdenterFunnet -> publiserTombstone(identerResult)
                    is IdenterIkkeFunnet -> TODO()
                    is IdenterOppslagFeil -> TODO()
                }
            }
            else publiserKontorTilordning(record.value())
        }
        return when (result.isSuccess) {
            true -> Commit()
            false -> {
                val message = "Kunne ikke publisere endring på ao-kontor til kafka topic: ${result.exceptionOrNull()?.message}"
                log.error(message, result.exceptionOrNull())
                Retry(message)
            }
        }
    }
}