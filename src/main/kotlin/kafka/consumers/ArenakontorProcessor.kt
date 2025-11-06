package kafka.consumers

import no.nav.db.Ident
import no.nav.domain.externalEvents.OppfolgingsperiodeEndret
import no.nav.kafka.processor.Commit
import no.nav.kafka.processor.RecordProcessingResult
import org.apache.kafka.common.serialization.Deserializer
import org.apache.kafka.common.serialization.Serde
import org.apache.kafka.common.serialization.Serializer
import org.apache.kafka.streams.processor.api.Record

class ArenakontorProcessor {
    companion object {
        const val processorName = "ArenakontorProcessor"

        val identSerde: Serde<Ident> = object : Serde<Ident> {
            override fun serializer(): Serializer<Ident> =
                Serializer<Ident> { topic, data -> data.toString().toByteArray() }
            override fun deserializer(): Deserializer<Ident> =
                Deserializer<Ident> { topic, data -> Ident.validateOrThrow(data.decodeToString(), Ident.HistoriskStatus.UKJENT) }
        }
        val oppfolgingsperiodeEndretSerde = jsonSerde<OppfolgingsperiodeEndret>()
    }

    fun process(record: Record<Ident, OppfolgingsperiodeEndret>): RecordProcessingResult<String, String> {
        return Commit()
    }
}