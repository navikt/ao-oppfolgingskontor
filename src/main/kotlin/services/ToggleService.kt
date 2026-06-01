package services

import io.getunleash.Unleash

const val STOPP_KAFKA_CONSUMERS_TOGGLE = "ao-kontor.stopp-kafkalyttere"

class ToggleService(
    private val unleashClient: Unleash,
) {
    fun skalIkkeLeseFraKafka(): Boolean {
        return unleashClient.isEnabled(STOPP_KAFKA_CONSUMERS_TOGGLE)
    }
}