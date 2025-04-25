package no.nav

import io.ktor.server.application.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.request.*
import org.slf4j.event.*

val excludedPaths = listOf("/isAlive", "/isReady", "/metrics")

fun Application.configureMonitoring() {
//    install(DropwizardMetrics) {
//        Slf4jReporter.forRegistry(registry)
//            .outputTo(this@configureMonitoring.log)
//            .convertRatesTo(TimeUnit.SECONDS)
//            .convertDurationsTo(TimeUnit.MILLISECONDS)
//            .build()
//            .start(10, TimeUnit.SECONDS)
//    }
    install(CallLogging) {
        level = Level.INFO
        filter { call ->
            val path = call.request.path()
            path.startsWith("/") && !excludedPaths.contains(path)
        }
        format { call ->
            val responseTime = call.processingTimeMillis()
            val status = call.response.status()?.value
            val method = call.request.httpMethod.value
            val path = call.request.path()
            "$status $method - $path in ${responseTime}ms"
        }
    }
}
