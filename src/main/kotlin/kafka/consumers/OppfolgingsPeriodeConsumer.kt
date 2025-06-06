package no.nav.kafka.consumers

import no.nav.kafka.processor.RecordProcessingResult
import no.nav.poao_tilgang.client_core.PoaoTilgangHttpClient
import org.apache.kafka.streams.processor.api.Record
import org.apache.kafka.streams.processor.api.RecordMetadata

class OppfolgingsPeriodeConsumer(
    private val poaoTilgangHttpClient: PoaoTilgangHttpClient =
) {
    fun consume(record: Record<String, String>, maybeRecordMetadata: RecordMetadata?): RecordProcessingResult {
        poaoTilgangHttpClient.hentTilgangsAttributter()
        return RecordProcessingResult.COMMIT
    }
}