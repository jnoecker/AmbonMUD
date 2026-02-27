package dev.ambon.metrics

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("integration")
class MetricsHttpServerTest {
    @Test
    fun `GET metrics returns 200 with prometheus text format`(): Unit =
        runBlocking {
            val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
            registry.counter("test_counter").increment()

            testApplication {
                application {
                    metricsModule(registry, "/metrics")
                }

                val response = client.get("/metrics")
                assertEquals(HttpStatusCode.OK, response.status)
                val body = response.bodyAsText()
                assertTrue(body.contains("# HELP"), "Expected Prometheus HELP comments in body")
            }
        }

    @Test
    fun `GET metrics returns text-plain content type`(): Unit =
        runBlocking {
            val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
            registry.counter("test_counter").increment()

            testApplication {
                application {
                    metricsModule(registry, "/metrics")
                }

                val response = client.get("/metrics")
                assertEquals(HttpStatusCode.OK, response.status)
                assertTrue(
                    response.headers["Content-Type"]?.contains("text/plain") == true,
                    "Expected text/plain content type",
                )
            }
        }
}
