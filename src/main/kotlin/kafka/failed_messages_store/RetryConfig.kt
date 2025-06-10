package no.nav.kafka.failed_messages_store

import java.time.Duration

data class RetryConfig(
    val stateStoreName: String = "postgres-retry-store",
    val maxRetries: Int = 5,
    val retryInterval: Duration = Duration.ofMinutes(1),
    val retryBatchSize: Int = 100
)