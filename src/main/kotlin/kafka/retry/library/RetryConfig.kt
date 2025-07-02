package no.nav.kafka.retry.library

import java.time.Duration

sealed class MaxRetries {
    class Finite(val maxRetries: Int) : MaxRetries()
    data object Infinite : MaxRetries()
}

data class RetryConfig(
    /* Each topic should have their own state-store */
    val stateStoreName: String,
    val maxRetries: MaxRetries = MaxRetries.Infinite,
    val retryInterval: Duration = Duration.ofMinutes(1),
    val retryBatchSize: Int = 100
)