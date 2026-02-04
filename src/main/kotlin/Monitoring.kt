package no.nav

import io.ktor.server.application.*
import io.ktor.server.metrics.micrometer.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.core.instrument.binder.system.UptimeMetrics
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import org.slf4j.event.*
import javax.sql.DataSource

val excludedPaths = listOf("/isAlive", "/isReady", "/metrics")

fun Application.configureMonitoring(dataSource: DataSource): MeterRegistry {
    val appMicrometerRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

    install(MicrometerMetrics) {
        registry = appMicrometerRegistry
        meterBinders = listOf(
            JvmMemoryMetrics(),
            JvmGcMetrics(),
            ProcessorMetrics(),
            UptimeMetrics()
        )
    }

    appMicrometerRegistry.gauge(
        "failed_messages_older_than_20_minutes",
        dataSource
    ) { ds ->
        try {
            ds.connection.use { connection ->
                connection.prepareStatement(
                    """
                    SELECT COUNT(*)
                    FROM failed_messages
                    WHERE queue_timestamp < NOW() - INTERVAL '20 minutes'
                    """
                ).use { statement ->
                    statement.executeQuery().use { rs ->
                        if (rs.next()) rs.getInt(1).toDouble() else 0.0
                    }
                }
            }
        } catch (e: Exception) {
            log.error("Failed to query failed_messages for metrics", e)
            0.0
        }
    }

    routing {
        get("/metrics") {
            call.respond(appMicrometerRegistry.scrape())
        }
    }
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
    return appMicrometerRegistry
}
