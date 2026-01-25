package dev.ambon.engine

import dev.ambon.domain.world.WorldFactory
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandParser
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.events.InboundEvent
import dev.ambon.engine.events.OutboundEvent
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

    private val world = WorldFactory.demoWorld()
    private val players = PlayerRegistry(world.startRoom)
    private val router = CommandRouter(world, players, outbound)

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
                players.connect(ev.sessionId)
                outbound.send(OutboundEvent.SendInfo(ev.sessionId, "Welcome to QuickMUD"))
                router.handle(ev.sessionId, Command.Look) // shows room + prompt
            }

            is InboundEvent.Disconnected -> {
                players.disconnect(ev.sessionId)
                sessions.onDisconnect(ev.sessionId)
            }

            is InboundEvent.LineReceived -> {
                val line = ev.line.trim()
                router.handle(ev.sessionId, CommandParser.parse(line))
            }
        }
    }
}
