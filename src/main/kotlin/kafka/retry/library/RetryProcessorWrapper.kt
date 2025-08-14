package kafka.retry.library

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import net.javacrumbs.shedlock.core.LockProvider
import no.nav.kafka.processor.RecordProcessingResult
import no.nav.kafka.retry.library.RetryConfig
import no.nav.kafka.retry.library.internal.RetryableRepository
import no.nav.kafka.retry.library.internal.RetryableProcessor
import org.apache.kafka.common.serialization.Serde
import org.apache.kafka.streams.processor.api.ProcessorSupplier
import org.apache.kafka.streams.processor.api.Record

object RetryProcessorWrapper {
    fun <KIn, VIn, KOut, VOut> wrapInRetryProcessor(
        /**
         *  Can be a topic or stream-name */
        topic: String,
        keyInSerde: Serde<KIn>,
        valueInSerde: Serde<VIn>,
        lockProvider: LockProvider,
        businessLogic: (Record<KIn, VIn>) -> RecordProcessingResult<KOut, VOut>,
        config: RetryConfig = RetryConfig(),
        repository: RetryableRepository = RetryableRepository(topic),
        punctuationCoroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO),
        streamType: StreamType
    ): ProcessorSupplier<KIn, VIn, KOut, VOut> {
        return ProcessorSupplier {
            RetryableProcessor(
                config = config,
                keyInSerde = keyInSerde,
                valueInSerde = valueInSerde,
                topic = topic,
                repository = repository,
                businessLogic = businessLogic,
                lockProvider = lockProvider,
                punctuationCoroutineScope = punctuationCoroutineScope,
                streamType = streamType
            )
        }
    }

}

enum class StreamType {
    SOURCE,                     // Kafka topic
    INTERNAL                    // Intern prosessor
}
