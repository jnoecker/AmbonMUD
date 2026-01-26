package dev.ambon.engine

import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.mob.MobState
import dev.ambon.domain.world.WorldFactory
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandParser
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.events.InboundEvent
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.scheduler.Scheduler
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.Clock

class GameEngine(
    private val inbound: ReceiveChannel<InboundEvent>,
    private val outbound: SendChannel<OutboundEvent>,
    private val players: PlayerRegistry,
    private val clock: Clock = Clock.systemUTC(),
    private val tickMillis: Long = 100L,
    private val scheduler: Scheduler = Scheduler(clock),
) {
    private val world = WorldFactory.demoWorld()

    private val mobs = MobRegistry()

    private val router = CommandRouter(world, players, mobs, outbound)

    init {
        mobs.upsert(MobState(MobId("demo:wolf"), name = "a wary wolf", roomId = world.startRoom))
        mobs.upsert(MobState(MobId("demo:owl"), name = "a silent owl", roomId = world.startRoom))
    }

    suspend fun run() =
        coroutineScope {
            while (isActive) {
                val tickStart = clock.millis()

                // Drain inbound without blocking
                while (true) {
                    val ev = inbound.tryReceive().getOrNull() ?: break
                    handle(ev)
                }

                // Run scheduled actions (bounded)
                scheduler.runDue(maxActions = 100)

                val elapsed = clock.millis() - tickStart
                val sleep = (tickMillis - elapsed).coerceAtLeast(0)
                delay(sleep)
            }
        }

    private suspend fun handle(ev: InboundEvent) {
        when (ev) {
            is InboundEvent.Connected -> {
                val sid = ev.sessionId

                players.connect(sid)

                val me = players.get(sid)
                if (me == null) {
                    // This should never happen; connect() must create state.
                    outbound.send(OutboundEvent.SendError(sid, "Internal error: player not initialized"))
                    outbound.send(OutboundEvent.Close(sid, "Internal error"))
                    return
                }

                broadcastToRoom(me.roomId, "${me.name} enters.", sid)
                outbound.send(OutboundEvent.SendInfo(sid, "Welcome to QuickMUD"))
                router.handle(sid, Command.Look) // room + prompt
            }

            is InboundEvent.Disconnected -> {
                val sid = ev.sessionId
                val me = players.get(sid)

                if (me != null) {
                    broadcastToRoom(me.roomId, "${me.name} leaves.", sid)
                }

                players.disconnect(sid) // idempotent; safe even if me == null
            }

            is InboundEvent.LineReceived -> {
                val sid = ev.sessionId

                // Optional safety: ignore input from unknown sessions
                if (players.get(sid) == null) return

                router.handle(sid, CommandParser.parse(ev.line))
            }
        }
    }

    private suspend fun broadcastToRoom(
        roomId: RoomId,
        text: String,
        excludeSid: SessionId? = null,
    ) {
        for (p in players.playersInRoom(roomId)) {
            if (excludeSid != null && p.sessionId == excludeSid) continue
            outbound.send(OutboundEvent.SendText(p.sessionId, text))
        }
    }
}
