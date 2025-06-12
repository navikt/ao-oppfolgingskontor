package no.nav.kafka.retry.library

import java.time.Duration

val INFINITE_RETRY = -1

data class RetryConfig(
    val stateStoreName: String = "postgres-retry-store",
    val maxRetries: Int = INFINITE_RETRY,
    val retryInterval: Duration = Duration.ofMinutes(1),
    val retryBatchSize: Int = 100
)