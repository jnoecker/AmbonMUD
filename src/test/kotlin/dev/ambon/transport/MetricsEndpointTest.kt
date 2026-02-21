package dev.ambon.transport

import dev.ambon.bus.LocalInboundBus
import dev.ambon.bus.LocalOutboundBus
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MetricsEndpointTest {
    @Test
    fun `GET metrics returns 200 with prometheus text when registry is provided`(): Unit =
        runBlocking {
            val inbound = LocalInboundBus()
            val engineOutbound = LocalOutboundBus()
            val outboundRouter = OutboundRouter(engineOutbound, this)
            val prometheusRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
            // Register a metric so the scrape output is non-empty
            prometheusRegistry.counter("test_metric").increment()

            testApplication {
                application {
                    ambonMUDWebModule(
                        inbound = inbound,
                        outboundRouter = outboundRouter,
                        sessionIdFactory = { dev.ambon.domain.ids.SessionId(1) },
                        prometheusRegistry = prometheusRegistry,
                        metricsEndpoint = "/metrics",
                    )
                }

                val response = client.get("/metrics")
                assertEquals(HttpStatusCode.OK, response.status)
                val body = response.bodyAsText()
                assertTrue(body.contains("# HELP"), "Expected Prometheus HELP comments in body")
                assertTrue(
                    response.headers["Content-Type"]?.contains("text/plain") == true,
                    "Expected text/plain content type",
                )
            }

            inbound.close()
            engineOutbound.close()
        }

    @Test
    fun `GET metrics returns 404 when no registry is provided`(): Unit =
        runBlocking {
            val inbound = LocalInboundBus()
            val engineOutbound = LocalOutboundBus()
            val outboundRouter = OutboundRouter(engineOutbound, this)

            testApplication {
                application {
                    ambonMUDWebModule(
                        inbound = inbound,
                        outboundRouter = outboundRouter,
                        sessionIdFactory = { dev.ambon.domain.ids.SessionId(1) },
                        prometheusRegistry = null,
                    )
                }

                val response = client.get("/metrics")
                assertEquals(HttpStatusCode.NotFound, response.status)
            }

            inbound.close()
            engineOutbound.close()
        }
}
