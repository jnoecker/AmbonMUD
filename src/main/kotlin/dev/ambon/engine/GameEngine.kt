package dev.ambon.engine

import dev.ambon.config.EngineConfig
import dev.ambon.config.LoginConfig
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
import dev.ambon.metrics.GameMetrics
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.Timer
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.Clock

private val log = KotlinLogging.logger {}

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
    private val maxInboundEventsPerTick: Int = Int.MAX_VALUE,
    private val loginConfig: LoginConfig = LoginConfig(),
    private val engineConfig: EngineConfig = EngineConfig(),
    private val progression: PlayerProgression = PlayerProgression(),
    private val metrics: GameMetrics = GameMetrics.noop(),
    private val onShutdown: suspend () -> Unit = {},
) {
    private val zoneResetDueAtMillis =
        world.zoneLifespansMinutes
            .filterValues { it > 0L }
            .mapValuesTo(mutableMapOf()) { (_, minutes) -> clock.millis() + minutesToMillis(minutes) }

    private val mobSystem =
        MobSystem(
            world = world,
            mobs = mobs,
            players = players,
            outbound = outbound,
            clock = clock,
            minWanderDelayMillis = engineConfig.mob.minWanderDelayMillis,
            maxWanderDelayMillis = engineConfig.mob.maxWanderDelayMillis,
            metrics = metrics,
        )
    private val combatSystem =
        CombatSystem(
            players = players,
            mobs = mobs,
            items = items,
            outbound = outbound,
            clock = clock,
            tickMillis = engineConfig.combat.tickMillis,
            minDamage = engineConfig.combat.minDamage,
            maxDamage = engineConfig.combat.maxDamage,
            detailedFeedbackEnabled = engineConfig.combat.feedback.enabled,
            detailedFeedbackRoomBroadcastEnabled = engineConfig.combat.feedback.roomBroadcastEnabled,
            onMobRemoved = mobSystem::onMobRemoved,
            progression = progression,
            metrics = metrics,
        )
    private val regenSystem =
        RegenSystem(
            players = players,
            items = items,
            clock = clock,
            baseIntervalMs = engineConfig.regen.baseIntervalMillis,
            minIntervalMs = engineConfig.regen.minIntervalMillis,
            msPerConstitution = engineConfig.regen.msPerConstitution,
            regenAmount = engineConfig.regen.regenAmount,
            metrics = metrics,
        )

    private val router =
        CommandRouter(
            world = world,
            players = players,
            mobs = mobs,
            items = items,
            combat = combatSystem,
            outbound = outbound,
            progression = progression,
            metrics = metrics,
            onShutdown = onShutdown,
            onMobSmited = mobSystem::onMobRemoved,
        )
    private val pendingLogins = mutableMapOf<SessionId, LoginState>()
    private val failedLoginAttempts = mutableMapOf<SessionId, Int>()
    private val sessionAnsiDefaults = mutableMapOf<SessionId, Boolean>()
    private val nameCommandRegex = Regex("^name\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val invalidNameMessage =
        "Invalid name. Use 2-16 chars: letters/digits/_ and cannot start with digit."
    private val invalidPasswordMessage = "Invalid password. Use 1-72 chars."
    private val maxWrongPasswordRetries = loginConfig.maxWrongPasswordRetries
    private val maxFailedLoginAttemptsBeforeDisconnect = loginConfig.maxFailedAttemptsBeforeDisconnect

    private sealed interface LoginState {
        data object AwaitingName : LoginState

        data class AwaitingCreateConfirmation(
            val name: String,
        ) : LoginState

        data class AwaitingExistingPassword(
            val name: String,
            val wrongPasswordAttempts: Int = 0,
        ) : LoginState

        data class AwaitingNewPassword(
            val name: String,
        ) : LoginState
    }

    init {
        world.mobSpawns.forEach { spawn ->
            mobs.upsert(
                MobState(
                    id = spawn.id, name = spawn.name, roomId = spawn.roomId,
                    hp = spawn.maxHp, maxHp = spawn.maxHp,
                    minDamage = spawn.minDamage, maxDamage = spawn.maxDamage,
                    armor = spawn.armor, xpReward = spawn.xpReward,
                    drops = spawn.drops,
                ),
            )
        }
        items.loadSpawns(world.itemSpawns)
        mobSystem.setCombatChecker(combatSystem::isMobInCombat)
    }

    suspend fun run() =
        coroutineScope {
            while (isActive) {
                val tickStart = clock.millis()
                val tickSample = Timer.start()

                try {
                    // Drain inbound without blocking
                    var inboundProcessed = 0
                    while (inboundProcessed < maxInboundEventsPerTick) {
                        val ev = inbound.tryReceive().getOrNull() ?: break
                        handle(ev)
                        inboundProcessed++
                    }
                    metrics.onInboundEventsProcessed(inboundProcessed)

                    // Simulate NPC actions (time-gated internally)
                    val mobSample = Timer.start()
                    val mobMoves = mobSystem.tick(maxMovesPerTick = engineConfig.mob.maxMovesPerTick)
                    mobSample.stop(metrics.mobSystemTickTimer)
                    metrics.onMobMoves(mobMoves)

                    // Simulate combat (time-gated internally)
                    val combatSample = Timer.start()
                    val combatsRan = combatSystem.tick(maxCombatsPerTick = engineConfig.combat.maxCombatsPerTick)
                    combatSample.stop(metrics.combatSystemTickTimer)
                    metrics.onCombatsProcessed(combatsRan)

                    // Regenerate player HP (time-gated internally)
                    val regenSample = Timer.start()
                    regenSystem.tick(maxPlayersPerTick = engineConfig.regen.maxPlayersPerTick)
                    regenSample.stop(metrics.regenTickTimer)

                    // Run scheduled actions (bounded)
                    val schedulerSample = Timer.start()
                    val (actionsRan, actionsDropped) = scheduler.runDue(maxActions = engineConfig.scheduler.maxActionsPerTick)
                    schedulerSample.stop(metrics.schedulerRunDueTimer)
                    metrics.onSchedulerActionsExecuted(actionsRan)
                    metrics.onSchedulerActionsDropped(actionsDropped)

                    // Reset zones when their lifespan elapses.
                    resetZonesIfDue()
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    log.error(t) { "Unhandled exception during tick processing" }
                }

                val elapsed = clock.millis() - tickStart
                val sleep = (tickMillis - elapsed).coerceAtLeast(0)

                metrics.onEngineTick()
                if (elapsed > tickMillis) metrics.onEngineTickOverrun()
                if (elapsed > tickMillis * 2) log.warn { "Slow tick: elapsed=${elapsed}ms (threshold=${tickMillis * 2}ms)" }
                tickSample.stop(metrics.engineTickTimer)

                delay(sleep)
            }
        }

    private suspend fun resetZonesIfDue() {
        if (zoneResetDueAtMillis.isEmpty()) return

        val now = clock.millis()
        for ((zone, dueAtMillis) in zoneResetDueAtMillis.toMap()) {
            if (now < dueAtMillis) continue

            resetZone(zone)

            val lifespanMinutes = world.zoneLifespansMinutes[zone] ?: continue
            val lifespanMillis = minutesToMillis(lifespanMinutes)
            val elapsedCycles = ((now - dueAtMillis) / lifespanMillis) + 1
            zoneResetDueAtMillis[zone] = dueAtMillis + (elapsedCycles * lifespanMillis)
        }
    }

    private suspend fun resetZone(zone: String) {
        val playersInZone = players.allPlayers().filter { player -> player.roomId.zone == zone }
        for (player in playersInZone) {
            outbound.send(OutboundEvent.SendText(player.sessionId, "The air shimmers as the area resets around you."))
        }

        val zoneRoomIds =
            world.rooms.keys
                .filterTo(linkedSetOf()) { roomId -> roomId.zone == zone }

        val zoneMobSpawns =
            world.mobSpawns
                .filter { spawn -> idZone(spawn.id.value) == zone }
        val activeZoneMobIds =
            mobs
                .all()
                .map { mob -> mob.id }
                .filter { mobId -> idZone(mobId.value) == zone }

        val zoneMobIds =
            (zoneMobSpawns.map { spawn -> spawn.id } + activeZoneMobIds)
                .toSet()

        for (mobId in zoneMobIds) {
            combatSystem.onMobRemovedExternally(mobId)
            mobs.remove(mobId)
            mobSystem.onMobRemoved(mobId)
        }

        for (spawn in zoneMobSpawns) {
            mobs.upsert(
                MobState(
                    id = spawn.id, name = spawn.name, roomId = spawn.roomId,
                    hp = spawn.maxHp, maxHp = spawn.maxHp,
                    minDamage = spawn.minDamage, maxDamage = spawn.maxDamage,
                    armor = spawn.armor, xpReward = spawn.xpReward,
                    drops = spawn.drops,
                ),
            )
            mobSystem.onMobSpawned(spawn.id)
        }

        val zoneItemSpawns =
            world.itemSpawns
                .filter { spawn -> idZone(spawn.instance.id.value) == zone }

        items.resetZone(
            zone = zone,
            roomIds = zoneRoomIds,
            mobIds = zoneMobIds,
            spawns = zoneItemSpawns,
        )
    }

    private suspend fun handle(ev: InboundEvent) {
        when (ev) {
            is InboundEvent.Connected -> {
                val sid = ev.sessionId

                pendingLogins[sid] = LoginState.AwaitingName
                failedLoginAttempts[sid] = 0
                sessionAnsiDefaults[sid] = ev.defaultAnsiEnabled
                outbound.send(OutboundEvent.ShowLoginScreen(sid))
                promptForName(sid)
            }

            is InboundEvent.Disconnected -> {
                val sid = ev.sessionId
                val me = players.get(sid)

                pendingLogins.remove(sid)
                failedLoginAttempts.remove(sid)
                sessionAnsiDefaults.remove(sid)

                combatSystem.onPlayerDisconnected(sid)
                regenSystem.onPlayerDisconnected(sid)

                if (me != null) {
                    log.info { "Player logged out: name=${me.name} sessionId=$sid" }
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
            is LoginState.AwaitingCreateConfirmation -> handleLoginCreateConfirmation(sessionId, line, state)
            is LoginState.AwaitingExistingPassword -> handleLoginExistingPassword(sessionId, line, state)
            is LoginState.AwaitingNewPassword -> handleLoginNewPassword(sessionId, line, state)
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

        if (players.hasRegisteredName(name)) {
            pendingLogins[sessionId] = LoginState.AwaitingExistingPassword(name)
            promptForExistingPassword(sessionId)
            return
        }

        pendingLogins[sessionId] = LoginState.AwaitingCreateConfirmation(name)
        promptForCreateConfirmation(sessionId, name)
    }

    private suspend fun handleLoginCreateConfirmation(
        sessionId: SessionId,
        line: String,
        state: LoginState.AwaitingCreateConfirmation,
    ) {
        when (line.trim().lowercase()) {
            "y",
            "yes",
            -> {
                pendingLogins[sessionId] = LoginState.AwaitingNewPassword(state.name)
                promptForNewPassword(sessionId)
            }

            "n",
            "no",
            -> {
                pendingLogins[sessionId] = LoginState.AwaitingName
                promptForName(sessionId)
            }

            else -> {
                outbound.send(OutboundEvent.SendError(sessionId, "Please answer yes or no."))
                promptForCreateConfirmation(sessionId, state.name)
            }
        }
    }

    private suspend fun handleLoginExistingPassword(
        sessionId: SessionId,
        line: String,
        state: LoginState.AwaitingExistingPassword,
    ) {
        val name = state.name
        val password = line
        if (password.isBlank()) {
            outbound.send(OutboundEvent.SendError(sessionId, "Blank password. Returning to login."))
            if (recordFailedLoginAttemptAndCloseIfNeeded(sessionId)) return
            pendingLogins[sessionId] = LoginState.AwaitingName
            promptForName(sessionId)
            return
        }

        when (val result = players.login(sessionId, name, password, defaultAnsiEnabled = sessionAnsiDefaults[sessionId] ?: false)) {
            LoginResult.Ok -> {
                finalizeSuccessfulLogin(sessionId)
            }

            LoginResult.InvalidName -> {
                outbound.send(OutboundEvent.SendError(sessionId, invalidNameMessage))
                if (recordFailedLoginAttemptAndCloseIfNeeded(sessionId)) return
                pendingLogins[sessionId] = LoginState.AwaitingName
                promptForName(sessionId)
            }

            LoginResult.InvalidPassword -> {
                outbound.send(OutboundEvent.SendError(sessionId, invalidPasswordMessage))
                promptForExistingPassword(sessionId)
            }

            LoginResult.Taken -> {
                outbound.send(OutboundEvent.SendError(sessionId, "That name is already taken."))
                pendingLogins[sessionId] = LoginState.AwaitingName
                promptForName(sessionId)
            }

            LoginResult.WrongPassword -> {
                val attempts = state.wrongPasswordAttempts + 1
                if (attempts > maxWrongPasswordRetries) {
                    outbound.send(OutboundEvent.SendError(sessionId, "Incorrect password too many times. Returning to login."))
                    if (recordFailedLoginAttemptAndCloseIfNeeded(sessionId)) return
                    pendingLogins[sessionId] = LoginState.AwaitingName
                    promptForName(sessionId)
                    return
                }

                val attemptsRemaining = (maxWrongPasswordRetries + 1) - attempts
                outbound.send(
                    OutboundEvent.SendError(
                        sessionId,
                        "Incorrect password. $attemptsRemaining attempt(s) before returning to login.",
                    ),
                )
                pendingLogins[sessionId] = state.copy(wrongPasswordAttempts = attempts)
                promptForExistingPassword(sessionId)
            }

            is LoginResult.Takeover -> {
                val oldSid = result.oldSessionId
                combatSystem.remapSession(oldSid, sessionId)
                regenSystem.remapSession(oldSid, sessionId)
                outbound.send(OutboundEvent.Close(oldSid, "Your account has logged in from another location."))
                val me = players.get(sessionId)
                if (me != null) broadcastToRoom(me.roomId, "${me.name} briefly flickers.", sessionId)
                finalizeSuccessfulLogin(sessionId, suppressEnterBroadcast = true)
            }
        }
    }

    private suspend fun handleLoginNewPassword(
        sessionId: SessionId,
        line: String,
        state: LoginState.AwaitingNewPassword,
    ) {
        val password = line
        if (password.isBlank()) {
            outbound.send(OutboundEvent.SendError(sessionId, "Blank password. Returning to login."))
            if (recordFailedLoginAttemptAndCloseIfNeeded(sessionId)) return
            pendingLogins[sessionId] = LoginState.AwaitingName
            promptForName(sessionId)
            return
        }

        when (players.create(sessionId, state.name, password, defaultAnsiEnabled = sessionAnsiDefaults[sessionId] ?: false)) {
            CreateResult.Ok -> {
                finalizeSuccessfulLogin(sessionId)
            }

            CreateResult.InvalidName -> {
                outbound.send(OutboundEvent.SendError(sessionId, invalidNameMessage))
                if (recordFailedLoginAttemptAndCloseIfNeeded(sessionId)) return
                pendingLogins[sessionId] = LoginState.AwaitingName
                promptForName(sessionId)
            }

            CreateResult.InvalidPassword -> {
                outbound.send(OutboundEvent.SendError(sessionId, invalidPasswordMessage))
                promptForNewPassword(sessionId)
            }

            CreateResult.Taken -> {
                outbound.send(OutboundEvent.SendError(sessionId, "That name is already taken."))
                if (recordFailedLoginAttemptAndCloseIfNeeded(sessionId)) return
                pendingLogins[sessionId] = LoginState.AwaitingName
                promptForName(sessionId)
            }
        }
    }

    private suspend fun recordFailedLoginAttemptAndCloseIfNeeded(sessionId: SessionId): Boolean {
        val nextAttempts = (failedLoginAttempts[sessionId] ?: 0) + 1
        failedLoginAttempts[sessionId] = nextAttempts
        if (nextAttempts < maxFailedLoginAttemptsBeforeDisconnect) return false

        pendingLogins.remove(sessionId)
        outbound.send(OutboundEvent.Close(sessionId, "Too many failed login attempts."))
        return true
    }

    private suspend fun finalizeSuccessfulLogin(
        sessionId: SessionId,
        suppressEnterBroadcast: Boolean = false,
    ) {
        pendingLogins.remove(sessionId)
        failedLoginAttempts.remove(sessionId)

        val me = players.get(sessionId)
        if (me == null) {
            outbound.send(OutboundEvent.SendError(sessionId, "Internal error: player not initialized"))
            outbound.send(OutboundEvent.Close(sessionId, "Internal error"))
            return
        }

        log.info { "Player logged in: name=${me.name} sessionId=$sessionId" }
        outbound.send(OutboundEvent.SetAnsi(sessionId, me.ansiEnabled))
        if (!suppressEnterBroadcast) {
            broadcastToRoom(me.roomId, "${me.name} enters.", sessionId)
        }
        router.handle(sessionId, Command.Look) // room + prompt
    }

    private suspend fun promptForName(sessionId: SessionId) {
        outbound.send(OutboundEvent.SendInfo(sessionId, "Enter your name:"))
        outbound.send(OutboundEvent.SendPrompt(sessionId))
    }

    private suspend fun promptForExistingPassword(sessionId: SessionId) {
        outbound.send(OutboundEvent.SendInfo(sessionId, "Password:"))
        outbound.send(OutboundEvent.SendPrompt(sessionId))
    }

    private suspend fun promptForNewPassword(sessionId: SessionId) {
        outbound.send(OutboundEvent.SendInfo(sessionId, "Create a password:"))
        outbound.send(OutboundEvent.SendPrompt(sessionId))
    }

    private suspend fun promptForCreateConfirmation(
        sessionId: SessionId,
        name: String,
    ) {
        outbound.send(OutboundEvent.SendInfo(sessionId, "No user named '$name' was found. Create a new user? (yes/no)"))
        outbound.send(OutboundEvent.SendPrompt(sessionId))
    }

    private fun extractLoginName(input: String): String {
        if (input.equals("name", ignoreCase = true)) return ""
        val match = nameCommandRegex.matchEntire(input)
        return match?.groupValues?.get(1)?.trim() ?: input
    }

    private fun idZone(rawId: String): String = rawId.substringBefore(':', rawId)

    private fun minutesToMillis(minutes: Long): Long = minutes * 60_000L
}
