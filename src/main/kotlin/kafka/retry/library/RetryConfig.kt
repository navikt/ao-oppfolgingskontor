package no.nav.kafka.retry.library

import java.time.Duration

data class RetryConfig(
    val stateStoreName: String = "postgres-retry-store",
    val maxRetries: Int = 5,
    val retryInterval: Duration = Duration.ofMinutes(1),
    val retryBatchSize: Int = 100
)