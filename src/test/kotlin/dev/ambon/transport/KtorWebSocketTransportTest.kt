package dev.ambon.transport

import dev.ambon.bus.InboundBus
import dev.ambon.bus.LocalInboundBus
import dev.ambon.bus.LocalOutboundBus
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.events.InboundEvent
import dev.ambon.engine.events.OutboundEvent
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("integration")
class KtorWebSocketTransportTest {
    @Test
    fun `websocket bridges inbound and outbound events`(): Unit =
        runBlocking {
            val inbound = LocalInboundBus()
            val engineOutbound = LocalOutboundBus()
            val outboundRouter = OutboundRouter(engineOutbound, this)
            val routerJob = outboundRouter.start()
            val sid = SessionId(42)

            testApplication {
                application {
                    ambonMUDWebModule(
                        inbound = inbound,
                        outboundRouter = outboundRouter,
                        sessionIdFactory = { sid },
                    )
                }

                val wsClient =
                    createClient {
                        install(WebSockets)
                    }

                wsClient.webSocket("/ws") {
                    assertEquals(
                        InboundEvent.Connected(sid, defaultAnsiEnabled = true),
                        withTimeout(3_000) { inbound.awaitReceive() },
                    )

                    // WebSocket transport auto-sends Core.Supports.Set on connect.
                    val gmcpAutoSend = withTimeout(3_000) { inbound.awaitReceive() }
                    assertTrue(
                        gmcpAutoSend is InboundEvent.GmcpReceived &&
                            gmcpAutoSend.gmcpPackage == "Core.Supports.Set",
                        "Expected auto Core.Supports.Set, got: $gmcpAutoSend",
                    )
                    gmcpAutoSend as InboundEvent.GmcpReceived
                    assertTrue(gmcpAutoSend.jsonData.contains("\"Room.Items 1\""))

                    send(Frame.Text("look\r\nwho"))
                    assertEquals(
                        InboundEvent.LineReceived(sid, "look"),
                        withTimeout(3_000) { inbound.awaitReceive() },
                    )
                    assertEquals(
                        InboundEvent.LineReceived(sid, "who"),
                        withTimeout(3_000) { inbound.awaitReceive() },
                    )

                    engineOutbound.send(OutboundEvent.SendText(sid, "hello"))
                    val payload =
                        withTimeout(3_000) { incoming.receive() }
                            .let { frame -> (frame as Frame.Text).readText() }
                    assertTrue(payload.contains("hello"))
                    assertTrue(payload.contains("\u001B["))
                }

                val disconnected = withTimeout(3_000) { inbound.awaitReceive() }
                assertTrue(disconnected is InboundEvent.Disconnected)
                assertEquals(sid, (disconnected as InboundEvent.Disconnected).sessionId)
            }

            routerJob.cancelAndJoin()
            inbound.close()
            engineOutbound.close()
        }

    @Test
    fun `serves v3 web client index page at root`(): Unit =
        runBlocking {
            val inbound = LocalInboundBus()
            val engineOutbound = LocalOutboundBus()
            val outboundRouter = OutboundRouter(engineOutbound, this)

            testApplication {
                application {
                    ambonMUDWebModule(
                        inbound = inbound,
                        outboundRouter = outboundRouter,
                        sessionIdFactory = { SessionId(1) },
                    )
                }

                val response = client.get("/")
                assertEquals(HttpStatusCode.OK, response.status)
                assertTrue(response.bodyAsText().contains("AmbonMUD"))
            }

            inbound.close()
            engineOutbound.close()
        }

    @Test
    fun `compresses and caches static web bundles while revalidating the shell`(): Unit =
        runBlocking {
            val inbound = LocalInboundBus()
            val engineOutbound = LocalOutboundBus()
            val outboundRouter = OutboundRouter(engineOutbound, this)

            testApplication {
                application {
                    ambonMUDWebModule(
                        inbound = inbound,
                        outboundRouter = outboundRouter,
                        sessionIdFactory = { SessionId(1) },
                    )
                }

                val indexResponse = client.get("/")
                assertEquals(REVALIDATE_WEB_CACHE_CONTROL, indexResponse.headers[HttpHeaders.CacheControl])
                val stylesheetPath =
                    Regex("""href="(/assets/[^"]+\.css)""")
                        .find(indexResponse.bodyAsText())
                        ?.groupValues
                        ?.get(1)
                assertTrue(stylesheetPath != null, "Built index must reference a hashed stylesheet")

                val stylesheetResponse =
                    client.get(stylesheetPath!!) {
                        header(HttpHeaders.AcceptEncoding, "gzip")
                    }
                assertEquals(HttpStatusCode.OK, stylesheetResponse.status)
                assertEquals(IMMUTABLE_WEB_CACHE_CONTROL, stylesheetResponse.headers[HttpHeaders.CacheControl])
                assertEquals("gzip", stylesheetResponse.headers[HttpHeaders.ContentEncoding])
            }

            inbound.close()
            engineOutbound.close()
        }

    @Test
    fun `redirects v3 web client path to root`(): Unit =
        runBlocking {
            val inbound = LocalInboundBus()
            val engineOutbound = LocalOutboundBus()
            val outboundRouter = OutboundRouter(engineOutbound, this)

            testApplication {
                application {
                    ambonMUDWebModule(
                        inbound = inbound,
                        outboundRouter = outboundRouter,
                        sessionIdFactory = { SessionId(1) },
                    )
                }

                val noRedirectClient =
                    createClient {
                        followRedirects = false
                    }
                val response = noRedirectClient.get("/v3/")
                assertEquals(HttpStatusCode.Found, response.status)
                assertEquals("/", response.headers["Location"])
            }

            inbound.close()
            engineOutbound.close()
        }

    @Test
    fun `origin matching allows same host`() {
        assertTrue(isOriginAllowedForHost("http://localhost:8080", "localhost:4000"))
        assertTrue(isOriginAllowedForHost("https://example.com", "example.com:443"))
        assertTrue(isOriginAllowedForHost("http://Example.Com", "example.com"))
    }

    @Test
    fun `origin matching rejects different host`() {
        assertFalse(isOriginAllowedForHost("http://evil.com", "localhost:4000"))
        assertFalse(isOriginAllowedForHost("http://attacker.example.com", "example.com"))
    }

    @Test
    fun `origin matching allows when Host header is absent`() {
        assertTrue(isOriginAllowedForHost("http://anything.com", null))
        assertTrue(isOriginAllowedForHost("http://anything.com", ""))
    }

    @Test
    fun `consecutive text frames preserve order end-to-end`(): Unit =
        runBlocking {
            // End-to-end smoke test: the router → queue → writer pipeline delivers every
            // SendText in the right order. Whether the writer coalesces is scheduling-
            // dependent (router and writer coroutines run on different dispatchers), so
            // this test only asserts ordering + completeness. The coalescing contract
            // itself is verified deterministically in WriteCoalescedOutboundFramesTest.
            val inbound = LocalInboundBus()
            val engineOutbound = LocalOutboundBus()
            val outboundRouter = OutboundRouter(engineOutbound, this)
            val routerJob = outboundRouter.start()
            val sid = SessionId(7)

            testApplication {
                application {
                    ambonMUDWebModule(
                        inbound = inbound,
                        outboundRouter = outboundRouter,
                        sessionIdFactory = { sid },
                    )
                }

                val wsClient = createClient { install(WebSockets) }

                wsClient.webSocket("/ws") {
                    withTimeout(3_000) { inbound.awaitReceive() } // Connected
                    withTimeout(3_000) { inbound.awaitReceive() } // Core.Supports.Set

                    engineOutbound.send(OutboundEvent.SendText(sid, "alpha"))
                    engineOutbound.send(OutboundEvent.SendText(sid, "beta"))
                    engineOutbound.send(OutboundEvent.SendText(sid, "gamma"))

                    val combined = StringBuilder()
                    withTimeout(3_000) {
                        while (!combined.contains("gamma")) {
                            combined.append((incoming.receive() as Frame.Text).readText())
                        }
                    }

                    assertTrue(combined.contains("alpha"), "missing alpha in: $combined")
                    assertTrue(combined.contains("beta"), "missing beta in: $combined")
                    assertTrue(combined.contains("gamma"), "missing gamma in: $combined")
                    assertTrue(
                        combined.indexOf("alpha") < combined.indexOf("beta"),
                        "alpha must precede beta in: $combined",
                    )
                    assertTrue(
                        combined.indexOf("beta") < combined.indexOf("gamma"),
                        "beta must precede gamma in: $combined",
                    )
                }

                val disconnected = withTimeout(3_000) { inbound.awaitReceive() }
                assertTrue(disconnected is InboundEvent.Disconnected)
            }

            routerJob.cancelAndJoin()
            inbound.close()
            engineOutbound.close()
        }

    @Test
    fun `gmcp frame stays isolated from surrounding text frames`(): Unit =
        runBlocking {
            val inbound = LocalInboundBus()
            val engineOutbound = LocalOutboundBus()
            val outboundRouter = OutboundRouter(engineOutbound, this)
            val routerJob = outboundRouter.start()
            val sid = SessionId(8)

            testApplication {
                application {
                    ambonMUDWebModule(
                        inbound = inbound,
                        outboundRouter = outboundRouter,
                        sessionIdFactory = { sid },
                    )
                }

                val wsClient = createClient { install(WebSockets) }

                wsClient.webSocket("/ws") {
                    withTimeout(3_000) { inbound.awaitReceive() } // Connected
                    withTimeout(3_000) { inbound.awaitReceive() } // Core.Supports.Set

                    // Text, GMCP, text — GMCP envelope must arrive in its own WS text frame
                    // so the client's parseGmcp can recognise it.
                    engineOutbound.send(OutboundEvent.SendText(sid, "before-gmcp"))
                    engineOutbound.send(
                        OutboundEvent.GmcpData(sid, "Room.Info", """{"id":"x"}"""),
                    )
                    engineOutbound.send(OutboundEvent.SendText(sid, "after-gmcp"))

                    val frames = mutableListOf<String>()
                    withTimeout(3_000) {
                        while (frames.size < 3) {
                            val f = incoming.receive()
                            frames += (f as Frame.Text).readText()
                        }
                    }

                    assertTrue(frames[0].contains("before-gmcp"))
                    assertEquals("""{"gmcp":"Room.Info","data":{"id":"x"}}""", frames[1])
                    assertTrue(frames[2].contains("after-gmcp"))
                }

                val disconnected = withTimeout(3_000) { inbound.awaitReceive() }
                assertTrue(disconnected is InboundEvent.Disconnected)
            }

            routerJob.cancelAndJoin()
            inbound.close()
            engineOutbound.close()
        }

    private suspend fun InboundBus.awaitReceive(): InboundEvent {
        while (true) {
            tryReceive().getOrNull()?.let { return it }
            delay(1)
        }
    }
}
