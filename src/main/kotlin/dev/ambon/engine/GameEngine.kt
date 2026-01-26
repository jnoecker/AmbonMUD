package dev.ambon.engine

import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.mob.MobState
import dev.ambon.domain.world.World
import dev.ambon.engine.auth.AuthFlow
import dev.ambon.engine.auth.AuthRegistry
import dev.ambon.engine.auth.Authed
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandParser
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.events.InboundEvent
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.engine.scheduler.Scheduler
import dev.ambon.persistence.AccountRepository
import dev.ambon.persistence.InMemoryAccountRepository
import dev.ambon.persistence.PlayerRepository
import dev.ambon.security.PasswordHasher
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
    private val playerRepo: PlayerRepository,
    private val world: World,
    private val mobs: MobRegistry,
    private val items: ItemRegistry,
    private val clock: Clock,
    private val tickMillis: Long,
    private val scheduler: Scheduler,
    private val accounts: AccountRepository = InMemoryAccountRepository(),
    private val passwordHasher: PasswordHasher = PasswordHasher(),
    private val authRegistry: AuthRegistry = AuthRegistry(),
) {
    private val mobSystem = MobSystem(world, mobs, players, outbound, clock = clock)

    private val router = CommandRouter(world, players, mobs, items, outbound)
    private val authFlow =
        AuthFlow(
            accounts = accounts,
            playersRepo = playerRepo,
            players = players,
            outbound = outbound,
            passwordHasher = passwordHasher,
            authRegistry = authRegistry,
            worldStartRoom = world.startRoom,
            clock = clock,
            postAuth = { sid ->
                val me = players.get(sid) ?: return@AuthFlow
                broadcastToRoom(me.roomId, "${me.name} enters.", sid)
                router.handle(sid, Command.Look)
            },
        )

    init {
        world.mobSpawns.forEach { mobs.upsert(MobState(it.id, it.name, it.roomId)) }
        items.loadSpawns(world.itemSpawns)
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

                // Simulate NPC actions (time-gated internally)
                mobSystem.tick(maxMovesPerTick = 10)

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

                authRegistry.onConnect(sid)
                authFlow.renderMenu(sid)
            }

            is InboundEvent.Disconnected -> {
                val sid = ev.sessionId
                val me = players.get(sid)

                if (me != null) {
                    broadcastToRoom(me.roomId, "${me.name} leaves.", sid)
                }

                authRegistry.onDisconnect(sid)
                players.disconnect(sid) // idempotent; safe even if me == null
            }

            is InboundEvent.LineReceived -> {
                val sid = ev.sessionId
                val state = authRegistry.get(sid)
                if (state !is Authed) {
                    authFlow.handleLine(sid, ev.line)
                    return
                }

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
