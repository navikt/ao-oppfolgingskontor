package no.nav.kafka.retry.library

import java.time.Duration

sealed class MaxRetries {
    /**
     * NB meldingen slettes hvis den har nådd maks antall forsøk.
     * Vær oppmerksom på at dette kan føre til tap av meldinger,
     * og at det fremdeles kan ligge nyere meldinger i køen for samme nøkkel.
     */
    class Finite(val maxRetries: Int) : MaxRetries()
    data object Infinite : MaxRetries()
}

data class RetryConfig(
    val maxRetries: MaxRetries = MaxRetries.Infinite,
    val retryInterval: Duration = Duration.ofSeconds(15),
    val retryBatchSize: Int = 5000
)