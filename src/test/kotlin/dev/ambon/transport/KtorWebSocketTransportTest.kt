package dev.ambon.transport

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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KtorWebSocketTransportTest {
    @Test
    fun `websocket bridges inbound and outbound events`(): Unit =
        runBlocking {
            val inbound = Channel<InboundEvent>(Channel.UNLIMITED)
            val engineOutbound = Channel<OutboundEvent>(Channel.UNLIMITED)
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
                        withTimeout(3_000) { inbound.receive() },
                    )

                    send(Frame.Text("look\r\nwho"))
                    assertEquals(
                        InboundEvent.LineReceived(sid, "look"),
                        withTimeout(3_000) { inbound.receive() },
                    )
                    assertEquals(
                        InboundEvent.LineReceived(sid, "who"),
                        withTimeout(3_000) { inbound.receive() },
                    )

                    engineOutbound.send(OutboundEvent.SendText(sid, "hello"))
                    val payload =
                        withTimeout(3_000) { incoming.receive() }
                            .let { frame -> (frame as Frame.Text).readText() }
                    assertTrue(payload.contains("hello"))
                    assertTrue(payload.contains("\u001B["))
                }

                val disconnected = withTimeout(3_000) { inbound.receive() }
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
            val inbound = Channel<InboundEvent>(Channel.UNLIMITED)
            val engineOutbound = Channel<OutboundEvent>(Channel.UNLIMITED)
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
}
