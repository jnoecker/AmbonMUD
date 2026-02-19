package dev.ambon.engine

import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.mob.MobState
import dev.ambon.domain.world.World
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandParser
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.events.InboundEvent
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
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
    private val world: World,
    private val mobs: MobRegistry,
    private val items: ItemRegistry,
    private val clock: Clock,
    private val tickMillis: Long,
    private val scheduler: Scheduler,
) {
    private val mobSystem = MobSystem(world, mobs, players, outbound, clock = clock)
    private val combatSystem = CombatSystem(players, mobs, items, outbound, clock = clock, onMobRemoved = mobSystem::onMobRemoved)

    private val router = CommandRouter(world, players, mobs, items, combatSystem, outbound)
    private val pendingLogins = mutableMapOf<SessionId, LoginState>()
    private val nameCommandRegex = Regex("^name\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val invalidNameMessage =
        "Invalid name. Use 2-16 chars: letters/digits/_ and cannot start with digit."
    private val invalidPasswordMessage = "Invalid password. Use 1-72 chars."

    private sealed interface LoginState {
        data object AwaitingName : LoginState

        data class AwaitingPassword(
            val name: String,
        ) : LoginState
    }

    init {
        world.mobSpawns.forEach { mobs.upsert(MobState(it.id, it.name, it.roomId)) }
        items.loadSpawns(world.itemSpawns)
        mobSystem.setCombatChecker(combatSystem::isMobInCombat)
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

                // Simulate combat (time-gated internally)
                combatSystem.tick(maxCombatsPerTick = 20)

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

                pendingLogins[sid] = LoginState.AwaitingName
                outbound.send(OutboundEvent.SendInfo(sid, "Welcome to QuickMUD"))
                promptForName(sid)
            }

            is InboundEvent.Disconnected -> {
                val sid = ev.sessionId
                val me = players.get(sid)

                pendingLogins.remove(sid)

                combatSystem.onPlayerDisconnected(sid)

                if (me != null) {
                    broadcastToRoom(me.roomId, "${me.name} leaves.", sid)
                }

                players.disconnect(sid) // idempotent; safe even if me == null
            }

            is InboundEvent.LineReceived -> {
                val sid = ev.sessionId

                val loginState = pendingLogins[sid]
                if (loginState != null) {
                    handleLoginLine(sid, ev.line, loginState)
                    return
                }

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

    private suspend fun handleLoginLine(
        sessionId: SessionId,
        line: String,
        state: LoginState,
    ) {
        when (state) {
            LoginState.AwaitingName -> handleLoginName(sessionId, line)
            is LoginState.AwaitingPassword -> handleLoginPassword(sessionId, line, state.name)
        }
    }

    private suspend fun handleLoginName(
        sessionId: SessionId,
        line: String,
    ) {
        val raw = line.trim()
        if (raw.isEmpty()) {
            outbound.send(OutboundEvent.SendError(sessionId, "Please enter a name."))
            promptForName(sessionId)
            return
        }

        val name = extractLoginName(raw)
        if (name.isEmpty()) {
            outbound.send(OutboundEvent.SendError(sessionId, "Please enter a name."))
            promptForName(sessionId)
            return
        }

        if (!players.isValidName(name)) {
            outbound.send(OutboundEvent.SendError(sessionId, invalidNameMessage))
            promptForName(sessionId)
            return
        }

        if (players.isNameOnline(name, sessionId)) {
            outbound.send(OutboundEvent.SendError(sessionId, "That name is already taken."))
            promptForName(sessionId)
            return
        }

        pendingLogins[sessionId] = LoginState.AwaitingPassword(name)
        promptForPassword(sessionId)
    }

    private suspend fun handleLoginPassword(
        sessionId: SessionId,
        line: String,
        name: String,
    ) {
        val password = line.trim()
        if (password.isEmpty()) {
            outbound.send(OutboundEvent.SendError(sessionId, "Please enter a password."))
            promptForPassword(sessionId)
            return
        }

        when (players.login(sessionId, name, password)) {
            LoginResult.Ok -> {
                pendingLogins.remove(sessionId)
                val me = players.get(sessionId)
                if (me == null) {
                    outbound.send(OutboundEvent.SendError(sessionId, "Internal error: player not initialized"))
                    outbound.send(OutboundEvent.Close(sessionId, "Internal error"))
                    return
                }
                broadcastToRoom(me.roomId, "${me.name} enters.", sessionId)
                router.handle(sessionId, Command.Look) // room + prompt
            }

            LoginResult.InvalidName -> {
                outbound.send(OutboundEvent.SendError(sessionId, invalidNameMessage))
                pendingLogins[sessionId] = LoginState.AwaitingName
                promptForName(sessionId)
            }

            LoginResult.InvalidPassword -> {
                outbound.send(OutboundEvent.SendError(sessionId, invalidPasswordMessage))
                promptForPassword(sessionId)
            }

            LoginResult.Taken -> {
                outbound.send(OutboundEvent.SendError(sessionId, "That name is already taken."))
                pendingLogins[sessionId] = LoginState.AwaitingName
                promptForName(sessionId)
            }

            LoginResult.WrongPassword -> {
                outbound.send(OutboundEvent.SendError(sessionId, "Incorrect password."))
                promptForPassword(sessionId)
            }
        }
    }

    private suspend fun promptForName(sessionId: SessionId) {
        outbound.send(OutboundEvent.SendInfo(sessionId, "Enter your name:"))
        outbound.send(OutboundEvent.SendPrompt(sessionId))
    }

    private suspend fun promptForPassword(sessionId: SessionId) {
        outbound.send(OutboundEvent.SendInfo(sessionId, "Password:"))
        outbound.send(OutboundEvent.SendPrompt(sessionId))
    }

    private fun extractLoginName(input: String): String {
        if (input.equals("name", ignoreCase = true)) return ""
        val match = nameCommandRegex.matchEntire(input)
        return match?.groupValues?.get(1)?.trim() ?: input
    }
}
