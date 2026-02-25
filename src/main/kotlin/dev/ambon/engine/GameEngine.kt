package dev.ambon.engine

import dev.ambon.bus.InboundBus
import dev.ambon.bus.OutboundBus
import dev.ambon.config.EngineConfig
import dev.ambon.config.LoginConfig
import dev.ambon.domain.PlayerClass
import dev.ambon.domain.Race
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.mob.MobState
import dev.ambon.domain.world.World
import dev.ambon.engine.QuestRegistry
import dev.ambon.engine.QuestSystem
import dev.ambon.engine.abilities.AbilityRegistry
import dev.ambon.engine.abilities.AbilityRegistryLoader
import dev.ambon.engine.abilities.AbilitySystem
import dev.ambon.engine.behavior.BehaviorTreeSystem
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandParser
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.PhaseResult
import dev.ambon.engine.dialogue.DialogueSystem
import dev.ambon.engine.events.InboundEvent
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.engine.scheduler.Scheduler
import dev.ambon.engine.status.EffectType
import dev.ambon.engine.status.StatusEffectRegistry
import dev.ambon.engine.status.StatusEffectRegistryLoader
import dev.ambon.engine.status.StatusEffectSystem
import dev.ambon.metrics.GameMetrics
import dev.ambon.sharding.BroadcastType
import dev.ambon.sharding.HandoffAckResult
import dev.ambon.sharding.HandoffManager
import dev.ambon.sharding.HandoffResult
import dev.ambon.sharding.InterEngineBus
import dev.ambon.sharding.InterEngineMessage
import dev.ambon.sharding.PlayerLocationIndex
import dev.ambon.sharding.PlayerSummary
import dev.ambon.sharding.ZoneInstance
import dev.ambon.sharding.ZoneRegistry
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.Timer
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.Clock
import java.util.UUID

private val log = KotlinLogging.logger {}

class GameEngine(
    private val inbound: InboundBus,
    private val outbound: OutboundBus,
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
    private val handoffManager: HandoffManager? = null,
    private val interEngineBus: InterEngineBus? = null,
    private val engineId: String = "",
    /** Returns the number of peer engines (excluding self). Used for `who` shard coverage warnings. */
    private val peerEngineCount: () -> Int = { 0 },
    /** Player-location index for O(1) cross-engine tell routing. */
    private val playerLocationIndex: PlayerLocationIndex? = null,
    /** Zone registry for instancing-aware phase command. */
    private val zoneRegistry: ZoneRegistry? = null,
    private val questRegistry: QuestRegistry = QuestRegistry(),
    private val achievementRegistry: AchievementRegistry = AchievementRegistry(),
) {
    private val zoneResetDueAtMillis =
        world.zoneLifespansMinutes
            .filterValues { it > 0L }
            .mapValuesTo(mutableMapOf()) { (_, minutes) -> clock.millis() + minutesToMillis(minutes) }

    /** GMCP packages each session has opted into (e.g. "Char.Vitals", "Room.Info"). */
    private val gmcpSessions = mutableMapOf<SessionId, MutableSet<String>>()

    /** Sessions whose vitals changed this tick and need a Char.Vitals push. */
    private val gmcpDirtyVitals = mutableSetOf<SessionId>()

    /** Mobs whose HP changed this tick and need a Room.UpdateMob push. */
    private val gmcpDirtyMobs = mutableSetOf<MobId>()

    /** Sessions whose status effects changed this tick and need a Char.StatusEffects push. */
    private val gmcpDirtyStatusEffects = mutableSetOf<SessionId>()

    val gmcpEmitter =
        GmcpEmitter(
            outbound = outbound,
            supportsPackage = { sid, pkg ->
                gmcpSessions[sid]?.any { supported ->
                    pkg == supported || pkg.startsWith("$supported.")
                } == true
            },
            progression = progression,
        )

    fun markVitalsDirty(sessionId: SessionId) {
        gmcpDirtyVitals.add(sessionId)
    }

    fun markMobHpDirty(mobId: MobId) {
        gmcpDirtyMobs.add(mobId)
    }

    fun markStatusDirty(sessionId: SessionId) {
        gmcpDirtyStatusEffects.add(sessionId)
    }

    private val mobSystem = MobSystem()
    private val statusEffectRegistry =
        StatusEffectRegistry().also { reg ->
            StatusEffectRegistryLoader.load(engineConfig.statusEffects, reg)
        }
    private val statusEffectSystem =
        StatusEffectSystem(
            registry = statusEffectRegistry,
            players = players,
            mobs = mobs,
            outbound = outbound,
            clock = clock,
            markVitalsDirty = ::markVitalsDirty,
            markMobHpDirty = ::markMobHpDirty,
            markStatusDirty = ::markStatusDirty,
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
            onMobRemoved = { mobId, roomId ->
                mobSystem.onMobRemoved(mobId)
                dialogueSystem.onMobRemoved(mobId)
                behaviorTreeSystem.onMobRemoved(mobId)
                for (p in players.playersInRoom(roomId)) {
                    gmcpEmitter.sendRoomRemoveMob(p.sessionId, mobId.value)
                }
                val spawn = world.mobSpawns.find { it.id == mobId }
                val respawnMs = spawn?.respawnSeconds?.let { it * 1_000L }
                if (spawn != null && respawnMs != null) {
                    scheduler.scheduleIn(respawnMs) {
                        if (mobs.get(spawn.id) != null) return@scheduleIn
                        if (world.rooms[spawn.roomId] == null) return@scheduleIn
                        val respawned = spawnToMobState(spawn)
                        mobs.upsert(respawned)
                        mobSystem.onMobSpawned(spawn.id)
                        behaviorTreeSystem.onMobSpawned(spawn.id)
                        for (p in players.playersInRoom(spawn.roomId)) {
                            outbound.send(OutboundEvent.SendText(p.sessionId, "${spawn.name} appears."))
                            gmcpEmitter.sendRoomAddMob(p.sessionId, respawned)
                        }
                    }
                }
            },
            progression = progression,
            metrics = metrics,
            strDivisor = engineConfig.combat.strDivisor,
            dexDodgePerPoint = engineConfig.combat.dexDodgePerPoint,
            maxDodgePercent = engineConfig.combat.maxDodgePercent,
            markVitalsDirty = ::markVitalsDirty,
            markMobHpDirty = ::markMobHpDirty,
            statusEffects = statusEffectSystem,
            onLevelUp = { sid, level ->
                markVitalsDirty(sid)
                val pc = players.get(sid)?.playerClass
                val newAbilities = abilitySystem.syncAbilities(sid, level, pc)
                for (ability in newAbilities) {
                    outbound.send(OutboundEvent.SendText(sid, "You have learned ${ability.displayName}!"))
                }
                val p = players.get(sid)
                if (p != null) {
                    gmcpEmitter.sendCharName(sid, p)
                    gmcpEmitter.sendCharSkills(sid, abilitySystem.knownAbilities(sid))
                }
                achievementSystem.onLevelReached(sid, level)
            },
            onMobKilledByPlayer = { sid, templateKey ->
                questSystem.onMobKilled(sid, templateKey)
                achievementSystem.onMobKilled(sid, templateKey)
            },
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
            manaBaseIntervalMs = engineConfig.regen.mana.baseIntervalMillis,
            manaMinIntervalMs = engineConfig.regen.mana.minIntervalMillis,
            manaRegenAmount = engineConfig.regen.mana.regenAmount,
            msPerWisdom = engineConfig.regen.mana.msPerWisdom,
            metrics = metrics,
            markVitalsDirty = ::markVitalsDirty,
        )
    private val abilityRegistry =
        AbilityRegistry().also { reg ->
            AbilityRegistryLoader.load(engineConfig.abilities, reg)
        }
    private val abilitySystem: AbilitySystem =
        AbilitySystem(
            players = players,
            registry = abilityRegistry,
            outbound = outbound,
            combat = combatSystem,
            clock = clock,
            items = items,
            intSpellDivisor = engineConfig.combat.intSpellDivisor,
            markVitalsDirty = ::markVitalsDirty,
            markMobHpDirty = ::markMobHpDirty,
            statusEffects = statusEffectSystem,
            markStatusDirty = ::markStatusDirty,
        )

    private val shopRegistry = ShopRegistry(items)

    private val dialogueSystem =
        DialogueSystem(
            mobs = mobs,
            players = players,
            outbound = outbound,
        )

    private val questSystem =
        QuestSystem(
            registry =
                questRegistry.also { reg ->
                    world.questDefinitions.forEach { reg.register(it) }
                },
            players = players,
            items = items,
            outbound = outbound,
            clock = clock,
        )

    private val achievementSystem =
        AchievementSystem(
            registry =
                achievementRegistry.also { reg ->
                    AchievementLoader.loadFromResource("world/achievements.yaml", reg)
                },
            players = players,
            outbound = outbound,
            gmcpEmitter = gmcpEmitter,
        )

    init {
        questSystem.onQuestCompleted = { sid, questId ->
            achievementSystem.onQuestCompleted(sid, questId)
        }
    }

    private val groupSystem =
        GroupSystem(
            players = players,
            outbound = outbound,
            clock = clock,
            maxGroupSize = engineConfig.group.maxSize,
            inviteTimeoutMs = engineConfig.group.inviteTimeoutMs,
        )

    private val behaviorTreeSystem: BehaviorTreeSystem =
        BehaviorTreeSystem(
            world = world,
            mobs = mobs,
            players = players,
            outbound = outbound,
            clock = clock,
            isMobInCombat = { mobId -> combatSystem.isMobInCombat(mobId) },
            isMobRooted = { mobId -> statusEffectSystem.hasMobEffect(mobId, EffectType.ROOT) },
            startMobCombat = { mobId, sessionId -> combatSystem.startMobCombat(mobId, sessionId) },
            fleeMob = { mobId -> combatSystem.fleeMob(mobId) },
            gmcpEmitter = gmcpEmitter,
            minActionDelayMs = engineConfig.mob.minActionDelayMillis,
            maxActionDelayMs = engineConfig.mob.maxActionDelayMillis,
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
            abilitySystem = abilitySystem,
            metrics = metrics,
            onShutdown = onShutdown,
            onMobSmited = mobSystem::onMobRemoved,
            onCrossZoneMove = if (handoffManager != null) ::handleCrossZoneMove else null,
            interEngineBus = interEngineBus,
            engineId = engineId,
            onRemoteWho = if (interEngineBus != null) ::handleRemoteWho else null,
            playerLocationIndex = playerLocationIndex,
            gmcpEmitter = gmcpEmitter,
            markVitalsDirty = ::markVitalsDirty,
            onPhase =
                if (zoneRegistry != null && zoneRegistry.instancingEnabled() && handoffManager != null) {
                    ::handlePhase
                } else {
                    null
                },
            statusEffects = statusEffectSystem,
            shopRegistry = shopRegistry,
            economyConfig = engineConfig.economy,
            dialogueSystem = dialogueSystem,
            questSystem = questSystem,
            registry = questRegistry,
            achievementSystem = achievementSystem,
            achievementRegistry = achievementRegistry,
            groupSystem = groupSystem,
        )

    private val pendingLogins = mutableMapOf<SessionId, LoginState>()
    private val failedLoginAttempts = mutableMapOf<SessionId, Int>()
    private val sessionAnsiDefaults = mutableMapOf<SessionId, Boolean>()
    private val pendingWhoRequests = mutableMapOf<String, PendingWhoRequest>()
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

        data class AwaitingRaceSelection(
            val name: String,
            val password: String,
        ) : LoginState

        data class AwaitingClassSelection(
            val name: String,
            val password: String,
            val race: Race,
        ) : LoginState
    }

    private data class PendingWhoRequest(
        val sessionId: SessionId,
        val deadlineEpochMs: Long,
        val expectedPeerCount: Int,
        val remotePlayerNames: MutableSet<String> = linkedSetOf(),
        var respondedCount: Int = 0,
    )

    init {
        world.mobSpawns.forEach { spawn ->
            mobs.upsert(spawnToMobState(spawn))
        }
        items.loadSpawns(world.itemSpawns)
        shopRegistry.register(world.shopDefinitions)
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

                    // Drain inter-engine messages (cross-zone handoffs, global commands)
                    if (interEngineBus != null) {
                        var interEngineProcessed = 0
                        while (interEngineProcessed < maxInboundEventsPerTick) {
                            val msg = interEngineBus.incoming().tryReceive().getOrNull() ?: break
                            handleInterEngineMessage(msg)
                            interEngineProcessed++
                        }
                    }

                    if (handoffManager != null) {
                        for (timedOut in handoffManager.expireTimedOut()) {
                            handleHandoffTimeout(timedOut.sessionId)
                        }
                    }
                    flushDueWhoResponses()

                    // Mob movement is handled entirely by BehaviorTreeSystem below
                    val mobSample = Timer.start()
                    val mobMoves = mobSystem.tick()
                    mobSample.stop(metrics.mobSystemTickTimer)
                    metrics.onMobMoves(mobMoves)

                    // Tick behavior trees for mobs with AI (time-gated internally)
                    behaviorTreeSystem.tick()

                    // Simulate combat (time-gated internally)
                    val combatSample = Timer.start()
                    val combatsRan = combatSystem.tick(maxCombatsPerTick = engineConfig.combat.maxCombatsPerTick)
                    combatSample.stop(metrics.combatSystemTickTimer)
                    metrics.onCombatsProcessed(combatsRan)

                    // Tick status effects (DOT/HOT/shield/expiry)
                    statusEffectSystem.tick(clock.millis())
                    // Handle mob kills from DOT ticks
                    for ((mobId, sourceSessionId) in statusEffectSystem.mobsKilledByDot()) {
                        val mob = mobs.get(mobId) ?: continue
                        if (sourceSessionId != null) {
                            combatSystem.handleSpellKill(sourceSessionId, mob)
                        } else {
                            // No source — end combat if applicable, broadcast death, clean up
                            combatSystem.onMobRemovedExternally(mobId)
                            dialogueSystem.onMobRemoved(mobId)
                            behaviorTreeSystem.onMobRemoved(mobId)
                            mobs.remove(mobId)
                            mobSystem.onMobRemoved(mobId)
                            statusEffectSystem.onMobRemoved(mobId)
                            broadcastToRoom(players, outbound, mob.roomId, "${mob.name} dies.")
                        }
                    }

                    // Regenerate player HP (time-gated internally)
                    val regenSample = Timer.start()
                    regenSystem.tick(maxPlayersPerTick = engineConfig.regen.maxPlayersPerTick)
                    regenSample.stop(metrics.regenTickTimer)

                    // Flush GMCP vitals for sessions that had changes this tick
                    flushDirtyGmcpVitals()
                    flushDirtyGmcpMobs()
                    flushDirtyGmcpStatusEffects()

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
            dialogueSystem.onMobRemoved(mobId)
            behaviorTreeSystem.onMobRemoved(mobId)
            mobs.remove(mobId)
            mobSystem.onMobRemoved(mobId)
        }

        for (spawn in zoneMobSpawns) {
            mobs.upsert(spawnToMobState(spawn))
            mobSystem.onMobSpawned(spawn.id)
            behaviorTreeSystem.onMobSpawned(spawn.id)
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

        // Refresh mob GMCP for all players in the reset zone
        for (player in playersInZone) {
            gmcpEmitter.sendRoomMobs(player.sessionId, mobs.mobsInRoom(player.roomId))
        }
    }

    private suspend fun handleCrossZoneMove(
        sessionId: SessionId,
        targetRoomId: RoomId,
    ) {
        val mgr = handoffManager ?: return
        // End combat and leave group before handoff
        combatSystem.endCombatFor(sessionId)
        regenSystem.onPlayerDisconnected(sessionId)
        statusEffectSystem.removeAllFromPlayer(sessionId)
        groupSystem.onPlayerDisconnected(sessionId)

        when (val result = mgr.initiateHandoff(sessionId, targetRoomId)) {
            is HandoffResult.Initiated -> {
                log.info { "Cross-zone handoff initiated to engine=${result.targetEngine.engineId}" }
            }
            HandoffResult.AlreadyInTransit -> {
                outbound.send(
                    OutboundEvent.SendInfo(sessionId, "You are already crossing into new territory."),
                )
                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }
            HandoffResult.PlayerNotFound -> {
                log.warn { "Cross-zone move failed: player not found for session=$sessionId" }
            }
            HandoffResult.NoEngineForZone -> {
                // No engine owns the target zone — fall back to shimmer message
                outbound.send(
                    OutboundEvent.SendText(sessionId, "The way shimmers but does not yield."),
                )
                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }
        }
    }

    private suspend fun handlePhase(
        sessionId: SessionId,
        targetHint: String?,
    ): PhaseResult {
        val reg = zoneRegistry ?: return PhaseResult.NotEnabled
        val mgr = handoffManager ?: return PhaseResult.NotEnabled

        val player = players.get(sessionId) ?: return PhaseResult.NoOp("You must be in the world to switch layers.")
        val currentZone = player.roomId.zone
        val instances = reg.instancesOf(currentZone)

        if (instances.size <= 1) {
            return PhaseResult.NoOp("There is only one instance of this zone.")
        }

        // No target: list available instances
        if (targetHint == null) {
            return PhaseResult.InstanceList(
                currentEngineId = engineId,
                instances = instances,
            )
        }

        // Resolve which instance to switch to
        val resolvedInstance =
            resolvePhaseTarget(targetHint, instances)
                ?: return PhaseResult.NoOp("Unknown instance or player: $targetHint")

        if (resolvedInstance.engineId == engineId) {
            return PhaseResult.NoOp("You are already on that instance.")
        }

        // Handoff to same room on the target engine
        combatSystem.endCombatFor(sessionId)
        regenSystem.onPlayerDisconnected(sessionId)
        statusEffectSystem.removeAllFromPlayer(sessionId)
        return when (mgr.initiateHandoff(sessionId, player.roomId, targetEngineOverride = resolvedInstance.address)) {
            is HandoffResult.Initiated -> PhaseResult.Initiated
            HandoffResult.AlreadyInTransit -> PhaseResult.NoOp("You are already crossing into new territory.")
            HandoffResult.PlayerNotFound -> PhaseResult.NoOp("Could not find your player data.")
            HandoffResult.NoEngineForZone -> PhaseResult.NoOp("That instance is no longer available.")
        }
    }

    private suspend fun resolvePhaseTarget(
        hint: String,
        instances: List<ZoneInstance>,
    ): ZoneInstance? {
        // 1. Match by engine ID
        instances.firstOrNull { it.engineId == hint }?.let { return it }

        // 2. Match by player name → look up which engine they're on
        val targetEngineId = playerLocationIndex?.lookupEngineId(hint)
        if (targetEngineId != null) {
            instances.firstOrNull { it.engineId == targetEngineId }?.let { return it }
        }

        // 3. Match by 1-based instance number
        val idx = hint.toIntOrNull()
        if (idx != null && idx in 1..instances.size) {
            return instances[idx - 1]
        }

        return null
    }

    private suspend fun handleRemoteWho(sessionId: SessionId) {
        val bus = interEngineBus ?: return
        val requestId = UUID.randomUUID().toString()
        pendingWhoRequests[requestId] =
            PendingWhoRequest(
                sessionId = sessionId,
                deadlineEpochMs = clock.millis() + WHO_RESPONSE_WAIT_MS,
                expectedPeerCount = peerEngineCount(),
            )
        bus.broadcast(
            InterEngineMessage.WhoRequest(
                requestId = requestId,
                replyToEngineId = engineId,
            ),
        )
    }

    private suspend fun flushDueWhoResponses(nowEpochMs: Long = clock.millis()) {
        if (pendingWhoRequests.isEmpty()) return

        val itr = pendingWhoRequests.iterator()
        while (itr.hasNext()) {
            val (_, pending) = itr.next()
            if (nowEpochMs < pending.deadlineEpochMs) continue

            if (players.get(pending.sessionId) != null) {
                if (pending.remotePlayerNames.isNotEmpty()) {
                    val names = pending.remotePlayerNames.sorted().joinToString(", ")
                    outbound.send(OutboundEvent.SendInfo(pending.sessionId, "Also online: $names"))
                }
                if (pending.expectedPeerCount > 0 && pending.respondedCount < pending.expectedPeerCount) {
                    outbound.send(
                        OutboundEvent.SendInfo(
                            pending.sessionId,
                            "(Note: some game shards did not respond — results may be incomplete.)",
                        ),
                    )
                }
            }
            itr.remove()
        }
    }

    private suspend fun handleHandoffTimeout(sessionId: SessionId) {
        if (players.get(sessionId) == null) return
        outbound.send(
            OutboundEvent.SendError(
                sessionId,
                "Cross-zone move timed out. You remain where you are.",
            ),
        )
        outbound.send(OutboundEvent.SendPrompt(sessionId))
    }

    private suspend fun handleInterEngineMessage(msg: InterEngineMessage) {
        when (msg) {
            is InterEngineMessage.PlayerHandoff -> {
                val mgr = handoffManager ?: return
                val sid = mgr.acceptHandoff(msg) ?: return
                // Register player on this engine (O(1) tell routing)
                playerLocationIndex?.register(msg.playerState.name)
                // Show the player their new surroundings
                router.handle(sid, Command.Look)
            }
            is InterEngineMessage.HandoffAck -> {
                val mgr = handoffManager ?: return
                when (val result = mgr.handleAck(msg)) {
                    is HandoffAckResult.Completed -> {
                        // Deregister from this engine; target engine will register on its side
                        playerLocationIndex?.unregister(result.playerName)
                        log.debug {
                            "Handoff ack completed: session=${msg.sessionId} " +
                                "targetEngine=${result.targetEngine.engineId}"
                        }
                    }
                    is HandoffAckResult.Failed -> {
                        val sid = SessionId(msg.sessionId)
                        if (players.get(sid) != null) {
                            val reason =
                                result.errorMessage
                                    ?.takeIf { it.isNotBlank() }
                                    ?: "Target engine rejected handoff."
                            outbound.send(OutboundEvent.SendError(sid, "Cross-zone move failed: $reason"))
                            outbound.send(OutboundEvent.SendPrompt(sid))
                        }
                        log.warn { "Handoff failed: session=${msg.sessionId} error=${result.errorMessage}" }
                    }
                    HandoffAckResult.NotPending -> {
                        log.debug { "Ignoring handoff ack for non-pending session=${msg.sessionId}" }
                    }
                }
            }
            is InterEngineMessage.GlobalBroadcast -> {
                // Skip broadcasts from ourselves (already delivered locally)
                if (msg.sourceEngineId == engineId) return
                when (msg.broadcastType) {
                    BroadcastType.GOSSIP -> {
                        for (p in players.allPlayers()) {
                            outbound.send(
                                OutboundEvent.SendText(p.sessionId, "[GOSSIP] ${msg.senderName}: ${msg.text}"),
                            )
                        }
                    }
                    BroadcastType.SHUTDOWN -> {
                        for (p in players.allPlayers()) {
                            outbound.send(
                                OutboundEvent.SendText(p.sessionId, "[SYSTEM] ${msg.text}"),
                            )
                        }
                        onShutdown()
                    }
                    BroadcastType.ANNOUNCEMENT -> {
                        for (p in players.allPlayers()) {
                            outbound.send(
                                OutboundEvent.SendText(p.sessionId, "[ANNOUNCEMENT] ${msg.text}"),
                            )
                        }
                    }
                }
            }
            is InterEngineMessage.TellMessage -> {
                // Deliver to local player if present
                val targetSid = players.findSessionByName(msg.toName) ?: return
                outbound.send(OutboundEvent.SendText(targetSid, "${msg.fromName} tells you: ${msg.text}"))
            }
            is InterEngineMessage.WhoRequest -> {
                // Don't reply to our own requests
                if (msg.replyToEngineId == engineId) return
                val localPlayers =
                    players.allPlayers().map {
                        PlayerSummary(name = it.name, roomId = it.roomId.value, level = it.level)
                    }
                interEngineBus?.sendTo(
                    msg.replyToEngineId,
                    InterEngineMessage.WhoResponse(
                        requestId = msg.requestId,
                        players = localPlayers,
                    ),
                )
            }
            is InterEngineMessage.WhoResponse -> {
                val pending = pendingWhoRequests[msg.requestId] ?: return
                if (players.get(pending.sessionId) == null) {
                    pendingWhoRequests.remove(msg.requestId)
                    return
                }
                pending.respondedCount++
                pending.remotePlayerNames += msg.players.map(PlayerSummary::name)
            }
            is InterEngineMessage.KickRequest -> {
                val targetSid = players.findSessionByName(msg.targetPlayerName) ?: return
                outbound.send(OutboundEvent.Close(targetSid, "Kicked by staff."))
            }
            is InterEngineMessage.ShutdownRequest -> {
                for (p in players.allPlayers()) {
                    outbound.send(
                        OutboundEvent.SendText(
                            p.sessionId,
                            "[SYSTEM] ${msg.initiatorName} has initiated a server shutdown. Goodbye!",
                        ),
                    )
                }
                onShutdown()
            }
            is InterEngineMessage.TransferRequest -> {
                val targetSid = players.findSessionByName(msg.targetPlayerName) ?: return
                val targetPlayer = players.get(targetSid) ?: return
                val targetRoomId = resolveRoomId(msg.targetRoomId, targetPlayer.roomId.zone) ?: return
                if (world.rooms.containsKey(targetRoomId)) {
                    // Room is on this engine — move locally
                    players.moveTo(targetSid, targetRoomId)
                    outbound.send(OutboundEvent.SendText(targetSid, "You are transported by a divine hand."))
                    router.handle(targetSid, Command.Look)
                } else if (handoffManager != null) {
                    // Room is on another engine — initiate handoff
                    combatSystem.endCombatFor(targetSid)
                    regenSystem.onPlayerDisconnected(targetSid)
                    statusEffectSystem.removeAllFromPlayer(targetSid)
                    when (handoffManager.initiateHandoff(targetSid, targetRoomId)) {
                        is HandoffResult.Initiated -> Unit
                        HandoffResult.PlayerNotFound -> Unit
                        HandoffResult.AlreadyInTransit -> Unit
                        HandoffResult.NoEngineForZone -> {
                            outbound.send(
                                OutboundEvent.SendError(
                                    targetSid,
                                    "The transfer destination is not currently available.",
                                ),
                            )
                            outbound.send(OutboundEvent.SendPrompt(targetSid))
                        }
                    }
                }
            }
            is InterEngineMessage.SessionRedirect -> {
                log.debug { "SessionRedirect received (handled at gateway layer)" }
            }
        }
    }

    /** Resolve a room ID string, adding current zone prefix if needed. */
    private fun resolveRoomId(
        arg: String,
        currentZone: String,
    ): RoomId? =
        if (':' in arg) {
            runCatching { RoomId(arg) }.getOrNull()
        } else {
            runCatching { RoomId("$currentZone:$arg") }.getOrNull()
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

            is InboundEvent.GmcpReceived -> {
                handleGmcpReceived(ev)
            }

            is InboundEvent.Disconnected -> {
                val sid = ev.sessionId
                val me = players.get(sid)

                pendingLogins.remove(sid)
                failedLoginAttempts.remove(sid)
                sessionAnsiDefaults.remove(sid)
                gmcpSessions.remove(sid)
                gmcpDirtyVitals.remove(sid)
                handoffManager?.cancelIfPending(sid)
                run {
                    val itr = pendingWhoRequests.iterator()
                    while (itr.hasNext()) {
                        if (itr.next().value.sessionId == sid) {
                            itr.remove()
                        }
                    }
                }

                combatSystem.onPlayerDisconnected(sid)
                regenSystem.onPlayerDisconnected(sid)
                abilitySystem.onPlayerDisconnected(sid)
                statusEffectSystem.onPlayerDisconnected(sid)
                dialogueSystem.onPlayerDisconnected(sid)
                groupSystem.onPlayerDisconnected(sid)
                gmcpDirtyStatusEffects.remove(sid)

                if (me != null) {
                    log.info { "Player logged out: name=${me.name} sessionId=$sid" }
                    playerLocationIndex?.unregister(me.name)
                    broadcastToRoom(players, outbound, me.roomId, "${me.name} leaves.", sid)
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
                if (handoffManager?.isInTransit(sid) == true) {
                    outbound.send(OutboundEvent.SendInfo(sid, "You are between zones. Please wait..."))
                    outbound.send(OutboundEvent.SendPrompt(sid))
                    return
                }

                router.handle(sid, CommandParser.parse(ev.line))
            }
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
            is LoginState.AwaitingRaceSelection -> handleLoginRaceSelection(sessionId, line, state)
            is LoginState.AwaitingClassSelection -> handleLoginClassSelection(sessionId, line, state)
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
                abilitySystem.remapSession(oldSid, sessionId)
                statusEffectSystem.remapSession(oldSid, sessionId)
                groupSystem.remapSession(oldSid, sessionId)
                outbound.send(OutboundEvent.Close(oldSid, "Your account has logged in from another location."))
                val me = players.get(sessionId)
                if (me != null) broadcastToRoom(players, outbound, me.roomId, "${me.name} briefly flickers.", sessionId)
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

        if (password.length > 72) {
            outbound.send(OutboundEvent.SendError(sessionId, invalidPasswordMessage))
            promptForNewPassword(sessionId)
            return
        }

        pendingLogins[sessionId] = LoginState.AwaitingRaceSelection(state.name, password)
        promptForRaceSelection(sessionId)
    }

    private suspend fun handleLoginRaceSelection(
        sessionId: SessionId,
        line: String,
        state: LoginState.AwaitingRaceSelection,
    ) {
        val input = line.trim()
        val races = Race.entries
        val race =
            input.toIntOrNull()?.let { num ->
                if (num in 1..races.size) races[num - 1] else null
            } ?: Race.fromString(input)

        if (race == null) {
            outbound.send(OutboundEvent.SendError(sessionId, "Invalid choice. Enter a number or race name."))
            promptForRaceSelection(sessionId)
            return
        }

        pendingLogins[sessionId] = LoginState.AwaitingClassSelection(state.name, state.password, race)
        promptForClassSelection(sessionId)
    }

    private suspend fun handleLoginClassSelection(
        sessionId: SessionId,
        line: String,
        state: LoginState.AwaitingClassSelection,
    ) {
        val input = line.trim()
        val classes = PlayerClass.entries
        val playerClass =
            input.toIntOrNull()?.let { num ->
                if (num in 1..classes.size) classes[num - 1] else null
            } ?: PlayerClass.fromString(input)

        if (playerClass == null) {
            outbound.send(OutboundEvent.SendError(sessionId, "Invalid choice. Enter a number or class name."))
            promptForClassSelection(sessionId)
            return
        }

        when (
            players.create(
                sessionId,
                state.name,
                state.password,
                defaultAnsiEnabled = sessionAnsiDefaults[sessionId] ?: false,
                race = state.race,
                playerClass = playerClass,
            )
        ) {
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
                pendingLogins[sessionId] = LoginState.AwaitingNewPassword(state.name)
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
        playerLocationIndex?.register(me.name)
        abilitySystem.syncAbilities(sessionId, me.level, me.playerClass)
        outbound.send(OutboundEvent.SetAnsi(sessionId, me.ansiEnabled))
        if (!ensureLoginRoomAvailable(sessionId, suppressEnterBroadcast)) return
        if (!suppressEnterBroadcast) {
            broadcastToRoom(players, outbound, me.roomId, "${me.name} enters.", sessionId)
        }
        // Send initial GMCP vitals/status for sessions that are already opted-in
        gmcpEmitter.sendCharStatusVars(sessionId)
        gmcpEmitter.sendCharVitals(sessionId, me)
        gmcpEmitter.sendCharName(sessionId, me)
        gmcpEmitter.sendCharItemsList(sessionId, items.inventory(sessionId), items.equipment(sessionId))
        gmcpEmitter.sendCharSkills(sessionId, abilitySystem.knownAbilities(sessionId))
        gmcpEmitter.sendCharStatusEffects(sessionId, statusEffectSystem.activePlayerEffects(sessionId))
        gmcpEmitter.sendCharAchievements(sessionId, me, achievementRegistry)
        router.handle(sessionId, Command.Look) // room + prompt (also sends Room.Info + Room.Players)
    }

    private suspend fun ensureLoginRoomAvailable(
        sessionId: SessionId,
        suppressEnterBroadcast: Boolean,
    ): Boolean {
        val me = players.get(sessionId) ?: return false
        if (world.rooms.containsKey(me.roomId)) return true

        val mgr = handoffManager
        if (mgr != null) {
            when (val handoffResult = mgr.initiateHandoff(sessionId, me.roomId)) {
                is HandoffResult.Initiated -> {
                    log.info {
                        "Player ${me.name} logged into remote room ${me.roomId.value}; " +
                            "handoff initiated to ${handoffResult.targetEngine.engineId}"
                    }
                    return false
                }
                HandoffResult.AlreadyInTransit -> {
                    return false
                }
                HandoffResult.PlayerNotFound -> {
                    outbound.send(
                        OutboundEvent.SendError(sessionId, "Internal error: handoff player missing during login."),
                    )
                    outbound.send(OutboundEvent.Close(sessionId, "Internal error"))
                    return false
                }
                HandoffResult.NoEngineForZone -> {
                    log.warn { "Saved login room ${me.roomId.value} is remote and has no engine owner" }
                }
            }
        } else {
            log.warn { "Saved login room ${me.roomId.value} is unavailable in non-sharded world; relocating to start" }
        }

        players.moveTo(sessionId, world.startRoom)
        val relocated = players.get(sessionId) ?: return false
        if (!suppressEnterBroadcast) {
            broadcastToRoom(players, outbound, relocated.roomId, "${relocated.name} enters.", sessionId)
        }
        outbound.send(
            OutboundEvent.SendError(
                sessionId,
                "Your saved location is unavailable. You have been moved to the starting room.",
            ),
        )
        router.handle(sessionId, Command.Look)
        return false
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

    private suspend fun promptForRaceSelection(sessionId: SessionId) {
        outbound.send(OutboundEvent.SendInfo(sessionId, "Choose your race:"))
        for ((index, race) in Race.entries.withIndex()) {
            val mods =
                buildList {
                    if (race.strMod != 0) add("STR %+d".format(race.strMod))
                    if (race.dexMod != 0) add("DEX %+d".format(race.dexMod))
                    if (race.conMod != 0) add("CON %+d".format(race.conMod))
                    if (race.intMod != 0) add("INT %+d".format(race.intMod))
                    if (race.wisMod != 0) add("WIS %+d".format(race.wisMod))
                    if (race.chaMod != 0) add("CHA %+d".format(race.chaMod))
                }.joinToString(", ")
            val desc = if (mods.isNotEmpty()) " ($mods)" else ""
            outbound.send(OutboundEvent.SendInfo(sessionId, "  ${index + 1}. ${race.displayName}$desc"))
        }
        outbound.send(OutboundEvent.SendPrompt(sessionId))
    }

    private suspend fun promptForClassSelection(sessionId: SessionId) {
        outbound.send(OutboundEvent.SendInfo(sessionId, "Choose your class:"))
        for ((index, pc) in PlayerClass.entries.withIndex()) {
            outbound.send(
                OutboundEvent.SendInfo(
                    sessionId,
                    "  ${index + 1}. ${pc.displayName} (+${pc.hpPerLevel} HP/lvl, +${pc.manaPerLevel} Mana/lvl)",
                ),
            )
        }
        outbound.send(OutboundEvent.SendPrompt(sessionId))
    }

    private fun extractLoginName(input: String): String {
        if (input.equals("name", ignoreCase = true)) return ""
        val match = nameCommandRegex.matchEntire(input)
        return match?.groupValues?.get(1)?.trim() ?: input
    }

    private suspend fun handleGmcpReceived(ev: InboundEvent.GmcpReceived) {
        val sid = ev.sessionId
        when (ev.gmcpPackage) {
            "Core.Hello" -> {
                log.debug { "GMCP Core.Hello from session=$sid data=${ev.jsonData}" }
            }
            "Core.Supports.Set" -> {
                val packages = parseGmcpPackageList(ev.jsonData)
                val supported = gmcpSessions.getOrPut(sid) { mutableSetOf() }
                supported.addAll(packages)
                log.debug { "GMCP supports set for session=$sid packages=$packages" }
                // Send initial data if the player is already logged in
                val player = players.get(sid) ?: return
                val room = world.rooms[player.roomId] ?: return
                gmcpEmitter.sendCharStatusVars(sid)
                gmcpEmitter.sendCharVitals(sid, player)
                gmcpEmitter.sendRoomInfo(sid, room)
                gmcpEmitter.sendCharName(sid, player)
                gmcpEmitter.sendCharItemsList(sid, items.inventory(sid), items.equipment(sid))
                gmcpEmitter.sendRoomPlayers(sid, players.playersInRoom(player.roomId).toList())
                gmcpEmitter.sendRoomMobs(sid, mobs.mobsInRoom(player.roomId))
                gmcpEmitter.sendCharSkills(sid, abilitySystem.knownAbilities(sid))
                gmcpEmitter.sendCharStatusEffects(sid, statusEffectSystem.activePlayerEffects(sid))
                gmcpEmitter.sendCharAchievements(sid, player, achievementRegistry)
            }
            "Core.Supports.Remove" -> {
                val packages = parseGmcpPackageList(ev.jsonData)
                gmcpSessions[sid]?.removeAll(packages.toSet())
            }
            "Core.Ping" -> {
                gmcpEmitter.sendCorePing(sid)
            }
        }
    }

    /**
     * Parses a GMCP package list like `["Char.Vitals 1","Room.Info 1"]`
     * into a set of package names (version suffix stripped).
     */
    private fun parseGmcpPackageList(json: String): List<String> {
        val content = json.trim().removePrefix("[").removeSuffix("]")
        if (content.isBlank()) return emptyList()
        return content
            .split(",")
            .map { it.trim().removeSurrounding("\"").trim() }
            .map { it.substringBefore(' ') }
            .filter { it.isNotBlank() }
    }

    /** Atomically snapshots and clears [set], returning the snapshot. */
    private fun <T> drainDirty(set: MutableSet<T>): List<T> {
        if (set.isEmpty()) return emptyList()
        val snapshot = set.toList()
        set.clear()
        return snapshot
    }

    private suspend fun flushDirtyGmcpVitals() {
        for (sid in drainDirty(gmcpDirtyVitals)) {
            val player = players.get(sid) ?: continue
            gmcpEmitter.sendCharVitals(sid, player)
        }
    }

    private suspend fun flushDirtyGmcpStatusEffects() {
        for (sid in drainDirty(gmcpDirtyStatusEffects)) {
            val effects = statusEffectSystem.activePlayerEffects(sid)
            gmcpEmitter.sendCharStatusEffects(sid, effects)
        }
    }

    private suspend fun flushDirtyGmcpMobs() {
        for (mobId in drainDirty(gmcpDirtyMobs)) {
            val mob = mobs.get(mobId) ?: continue
            for (p in players.playersInRoom(mob.roomId)) {
                gmcpEmitter.sendRoomUpdateMob(p.sessionId, mob)
            }
        }
    }

    private fun spawnToMobState(spawn: dev.ambon.domain.world.MobSpawn): MobState =
        MobState(
            id = spawn.id,
            name = spawn.name,
            roomId = spawn.roomId,
            hp = spawn.maxHp,
            maxHp = spawn.maxHp,
            minDamage = spawn.minDamage,
            maxDamage = spawn.maxDamage,
            armor = spawn.armor,
            xpReward = spawn.xpReward,
            drops = spawn.drops,
            goldMin = spawn.goldMin,
            goldMax = spawn.goldMax,
            dialogue = spawn.dialogue,
            behaviorTree = spawn.behaviorTree,
            templateKey = spawn.id.value,
            questIds = spawn.questIds,
        )

    private fun idZone(rawId: String): String = rawId.substringBefore(':', rawId)

    private fun minutesToMillis(minutes: Long): Long = minutes * 60_000L

    companion object {
        private const val WHO_RESPONSE_WAIT_MS = 300L
    }
}
