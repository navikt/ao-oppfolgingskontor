package services

import io.getunleash.DefaultUnleash
import io.getunleash.Unleash
import io.getunleash.event.ClientFeaturesResponse
import io.getunleash.event.UnleashSubscriber
import io.getunleash.util.UnleashConfig
import io.ktor.server.application.ApplicationEnvironment

const val STOPP_KAFKA_CONSUMERS_TOGGLE = "ao-kontor.stopp-kafkalyttere"
const val BRUK_AO_RUTING = "bruk_ao_kontor_som_master"

fun ApplicationEnvironment.createUnleashClient(subscriber: UnleashSubscriber) = DefaultUnleash(
    UnleashConfig
        .builder()
        .appName(this.getApplicationName())
        .instanceId(this.getApplicationName())
        .unleashAPI("${this.getUnleashServerApiUrl()}/api")
        .apiKey(this.getUnleashServerApiToken())
        .fetchTogglesInterval(10) // In seconds
        .subscriber(subscriber)
        .build()
)

class ToggleService(
    environment: ApplicationEnvironment,
    private val onKafkaPaused: () -> Unit,
    private val onKafkaResumed: () -> Unit,

) {
    public var brukAoRutingMutableVar: Boolean = skalBrukeAoRuting()

    private val subscriber =  object : UnleashSubscriber {
        override fun togglesFetched(toggleResponse: ClientFeaturesResponse) {
            refreshToggles()
        }
    }
    private val unleashClient: Unleash = environment.createUnleashClient(subscriber)
    private var isKafkaPaused = false

    private fun refreshToggles() {
        val skalPause = skalIkkeLeseFraKafka()
        if (!isKafkaPaused && skalPause) {
            onKafkaPaused()
        }
        if (isKafkaPaused && !skalPause) {
            onKafkaResumed()
        }

        brukAoRutingMutableVar = skalBrukeAoRuting()
    }

    fun skalIkkeLeseFraKafka(): Boolean {
        return unleashClient.isEnabled(STOPP_KAFKA_CONSUMERS_TOGGLE)
    }

    fun skalBrukeAoRuting(): Boolean {
        return unleashClient.isEnabled(BRUK_AO_RUTING)
    }
}

fun ApplicationEnvironment.getApplicationName(): String {
    return config.property("appName").getString()
}

fun ApplicationEnvironment.getUnleashServerApiUrl(): String {
    return config.property("unleash.url").getString()
}

fun ApplicationEnvironment.getUnleashServerApiToken(): String {
    return config.property("unleash.token").getString()
}