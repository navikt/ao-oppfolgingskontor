package kafka.retry.library.internal

import java.util.concurrent.ConcurrentHashMap

/**
 * En pod-intern låsemekanisme som er finkornet per topic.
 * Sikrer at kun én tråd om gangen jobber med en gitt topic,
 * men tillater parallell prosessering av ulike topics.
 */
@PublishedApi
internal object TopicLevelLock {
    // En Map som holder styr på hvilke topics som er "låst".
    private val locks = ConcurrentHashMap<String, Boolean>()

    /**
     * Prøver å skaffe en lås for et spesifikt topic.
     */
    fun tryAcquire(topic: String): Boolean {
        // 'putIfAbsent' er en atomisk operasjon.
        // Den legger inn 'true' KUN HVIS nøkkelen (topic) ikke allerede finnes.
        // Den returnerer 'null' hvis vi lyktes (nøkkelen var fraværende),
        // og den eksisterende verdien hvis vi mislyktes (nøkkelen var der).
        return locks.putIfAbsent(topic, true) == null
    }

    /**
     * Frigir låsen for et spesifikt topic.
     */
    fun release(topic: String) {
        locks.remove(topic)
    }
}