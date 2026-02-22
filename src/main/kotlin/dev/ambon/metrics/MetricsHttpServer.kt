package dev.ambon.metrics

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry

private val log = KotlinLogging.logger {}

class MetricsHttpServer(
    private val port: Int,
    private val registry: PrometheusMeterRegistry,
    private val endpoint: String = "/metrics",
) {
    private var engine: ApplicationEngine? = null

    fun start() {
        engine =
            embeddedServer(Netty, port = port) {
                metricsModule(registry, endpoint)
            }.start(wait = false)
        log.info { "Metrics HTTP server started on port $port (endpoint=$endpoint)" }
    }

    fun stop() {
        engine?.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
        engine = null
    }
}

internal fun Application.metricsModule(
    registry: PrometheusMeterRegistry,
    endpoint: String = "/metrics",
) {
    routing {
        get(endpoint) {
            call.respondText(
                contentType = ContentType.parse("text/plain; version=0.0.4; charset=utf-8"),
                text = registry.scrape(),
            )
        }
    }
}
