package dev.ambon.engine

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.Clock

class GameEngine(
    private val inbound: ReceiveChannel<InboundEvent>,
    private val outbound: SendChannel<OutboundEvent>,
    private val clock: Clock = Clock.systemUTC(),
    private val tickMillis: Long = 100L,
) {
    private val sessions = SessionRegistry()

    suspend fun run() =
        coroutineScope {
            while (isActive) {
                val tickStart = clock.millis()

                // Drain inbound without blocking
                while (true) {
                    val ev = inbound.tryReceive().getOrNull() ?: break
                    handle(ev)
                }

                // TODO: scheduler/world tick later

                val elapsed = clock.millis() - tickStart
                val sleep = (tickMillis - elapsed).coerceAtLeast(0)
                delay(sleep)
            }
        }

    private suspend fun handle(ev: InboundEvent) {
        when (ev) {
            is InboundEvent.Connected -> {
                sessions.onConnect(ev.sessionId)
                outbound.send(OutboundEvent.SendText(ev.sessionId, "Welcome to QuickMUD"))
                outbound.send(OutboundEvent.SendPrompt(ev.sessionId))
            }

            is InboundEvent.Disconnected -> {
                sessions.onDisconnect(ev.sessionId)
            }

            is InboundEvent.LineReceived -> {
                val line = ev.line.trim()
                when (line.lowercase()) {
                    "quit", "exit" -> {
                        outbound.send(OutboundEvent.Close(ev.sessionId, "Goodbye!"))
                    }

                    "" -> {
                        outbound.send(OutboundEvent.SendPrompt(ev.sessionId))
                    }

                    "ansi on" -> {
                        outbound.send(OutboundEvent.SetAnsi(ev.sessionId, true))
                        outbound.send(OutboundEvent.SendText(ev.sessionId, "ANSI enabled"))
                        outbound.send(OutboundEvent.SendPrompt(ev.sessionId))
                    }

                    "ansi off" -> {
                        outbound.send(OutboundEvent.SetAnsi(ev.sessionId, false))
                        outbound.send(OutboundEvent.SendText(ev.sessionId, "ANSI disabled"))
                        outbound.send(OutboundEvent.SendPrompt(ev.sessionId))
                    }

                    else -> {
                        outbound.send(OutboundEvent.SendText(ev.sessionId, "You said: $line"))
                        outbound.send(OutboundEvent.SendPrompt(ev.sessionId))
                    }
                }
            }
        }
    }
}
