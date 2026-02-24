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
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
    fun `serves web client index page`(): Unit =
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
                assertTrue(response.bodyAsText().contains("AmbonMUD Demo"))
            }

            inbound.close()
            engineOutbound.close()
        }

    @Test
    fun `splitIncomingLines supports mixed newlines`() {
        assertEquals(
            listOf("alpha", "bravo", "charlie", "delta"),
            splitIncomingLines("alpha\r\nbravo\ncharlie\rdelta"),
        )
        assertEquals(listOf(""), splitIncomingLines(""))
        assertEquals(listOf("line"), splitIncomingLines("line"))
        assertEquals(listOf("", ""), splitIncomingLines("\r\n\r\n"))
    }

    @Test
    fun `sanitizeIncomingLines enforces max length and non-printable limits`() {
        val longLine = "x".repeat(5)
        val longEx =
            assertThrows(ProtocolViolation::class.java) {
                sanitizeIncomingLines(longLine, maxLineLen = 4, maxNonPrintablePerLine = 10)
            }
        assertTrue(longEx.message!!.contains("Line too long"))

        val nonPrintable = "ok\u0001bad"
        val nonPrintableEx =
            assertThrows(ProtocolViolation::class.java) {
                sanitizeIncomingLines(nonPrintable, maxLineLen = 20, maxNonPrintablePerLine = 0)
            }
        assertTrue(nonPrintableEx.message!!.contains("non-printable"))
    }

    private suspend fun InboundBus.awaitReceive(): InboundEvent {
        while (true) {
            tryReceive().getOrNull()?.let { return it }
            delay(1)
        }
    }
}
