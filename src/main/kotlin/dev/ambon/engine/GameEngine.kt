package dev.ambon.engine

import dev.ambon.bus.InboundBus
import dev.ambon.bus.OutboundBus
import dev.ambon.config.EngineConfig
import dev.ambon.config.LoginConfig
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.RoomFeature
import dev.ambon.domain.world.World
import dev.ambon.engine.QuestRegistry
import dev.ambon.engine.QuestSystem
import dev.ambon.engine.abilities.AbilityRegistry
import dev.ambon.engine.abilities.AbilityRegistryLoader
import dev.ambon.engine.abilities.AbilitySystem
import dev.ambon.engine.behavior.BehaviorTreeSystem
import dev.ambon.engine.commands.CommandParser
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.PhaseResult
import dev.ambon.engine.commands.handlers.AdminHandler
import dev.ambon.engine.commands.handlers.CombatHandler
import dev.ambon.engine.commands.handlers.CommunicationHandler
import dev.ambon.engine.commands.handlers.DialogueQuestHandler
import dev.ambon.engine.commands.handlers.EngineContext
import dev.ambon.engine.commands.handlers.GroupHandler
import dev.ambon.engine.commands.handlers.ItemHandler
import dev.ambon.engine.commands.handlers.NavigationHandler
import dev.ambon.engine.commands.handlers.ProgressionHandler
import dev.ambon.engine.commands.handlers.ShopHandler
import dev.ambon.engine.commands.handlers.UiHandler
import dev.ambon.engine.commands.handlers.WorldFeaturesHandler
import dev.ambon.engine.dialogue.DialogueSystem
import dev.ambon.engine.events.DefaultEngineEventDispatcher
import dev.ambon.engine.events.EngineEventDispatcher
import dev.ambon.engine.events.GmcpEventHandler
import dev.ambon.engine.events.GmcpFlushHandler
import dev.ambon.engine.events.InboundEvent
import dev.ambon.engine.events.InputEventHandler
import dev.ambon.engine.events.InterEngineEventHandler
import dev.ambon.engine.events.LoginEventHandler
import dev.ambon.engine.events.LoginState
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.events.PhaseEventHandler
import dev.ambon.engine.events.SessionEventHandler
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.engine.scheduler.Scheduler
import dev.ambon.engine.status.EffectType
import dev.ambon.engine.status.StatusEffectRegistry
import dev.ambon.engine.status.StatusEffectRegistryLoader
import dev.ambon.engine.status.StatusEffectSystem
import dev.ambon.metrics.GameMetrics
import dev.ambon.persistence.WorldStateRepository
import dev.ambon.sharding.HandoffManager
import dev.ambon.sharding.InterEngineBus
import dev.ambon.sharding.InterEngineMessage
import dev.ambon.sharding.PlayerLocationIndex
import dev.ambon.sharding.ZoneRegistry
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.Timer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.yield
import java.time.Clock

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
    private val inboundBudgetMs: Long = 30L,
    private val loginConfig: LoginConfig = LoginConfig(),
    private val engineConfig: EngineConfig = EngineConfig(),
    private val progression: PlayerProgression = PlayerProgression(),
    private val metrics: GameMetrics = GameMetrics.noop(),
    private val onShutdown: suspend () -> Unit = {},
    private val onWorldReimport: suspend () -> Int = { 0 },
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
    /** Mutable world feature state (doors, containers, levers). Caller owns and must pass the same instance to the persistence worker. */
    private val worldState: WorldStateRegistry = WorldStateRegistry(world),
    /** Repository for persisting world feature states across restarts. */
    private val worldStateRepository: WorldStateRepository? = null,
) {
    private val loginFlowHandler by lazy {
        dev.ambon.engine.events.LoginFlowHandler(
            outbound = outbound,
            players = players,
            world = world,
            items = items,
            abilitySystem = abilitySystem,
            gmcpEmitter = gmcpEmitter,
            statusEffectSystem = statusEffectSystem,
            achievementRegistry = achievementRegistry,
            groupSystem = groupSystem,
            combatSystem = combatSystem,
            regenSystem = regenSystem,
            router = router,
            playerLocationIndex = playerLocationIndex,
            handoffManager = handoffManager,
            getEngineScope = { engineScope },
            metrics = metrics,
            maxWrongPasswordRetries = loginConfig.maxWrongPasswordRetries,
            maxFailedLoginAttemptsBeforeDisconnect = loginConfig.maxFailedAttemptsBeforeDisconnect,
        )
    }

    private val sessionEventHandler by lazy {
        SessionEventHandler(
            players = players,
            markAwaitingName = { sid -> loginFlowHandler.pendingLogins[sid] = LoginState.AwaitingName },
            clearLoginState = { sid -> loginFlowHandler.pendingLogins.remove(sid) },
            failedLoginAttempts = loginFlowHandler.failedLoginAttempts,
            sessionAnsiDefaults = loginFlowHandler.sessionAnsiDefaults,
            gmcpSessions = gmcpSessions,
            gmcpDirtyVitals = gmcpDirtyVitals,
            gmcpDirtyStatusEffects = gmcpDirtyStatusEffects,
            gmcpDirtyGroup = gmcpDirtyGroup,
            handoffManager = handoffManager,
            removePendingWhoRequestsFor = interEngineEventHandler::removePendingWhoRequestsFor,
            combatSystem = combatSystem,
            regenSystem = regenSystem,
            abilitySystem = abilitySystem,
            statusEffectSystem = statusEffectSystem,
            dialogueSystem = dialogueSystem,
            groupSystem = groupSystem,
            promptForName = loginFlowHandler::promptForName,
            showLoginScreen = { sid -> outbound.send(OutboundEvent.ShowLoginScreen(sid)) },
            onPlayerLoggedOut = { player, sid ->
                log.info { "Player logged out: name=${player.name} sessionId=$sid" }
                playerLocationIndex?.unregister(player.name)
                broadcastToRoom(players, outbound, player.roomId, "${player.name} leaves.", sid)
            },
            metrics = metrics,
        )
    }

    private val inputEventHandler by lazy {
        InputEventHandler<LoginState>(
            getLoginState = { sid -> loginFlowHandler.pendingLogins[sid] },
            hasActivePlayer = { sid -> players.get(sid) != null },
            isInTransit = { sid -> handoffManager?.isInTransit(sid) == true },
            handleLoginLine = ::handleLoginLine,
            onSessionInTransit = { sid ->
                outbound.send(OutboundEvent.SendInfo(sid, "You are between zones. Please wait..."))
                outbound.send(OutboundEvent.SendPrompt(sid))
            },
            routeCommandLine = { sid, line -> router.handle(sid, CommandParser.parse(line)) },
            metrics = metrics,
        )
    }

    private val loginEventHandler by lazy {
        LoginEventHandler<
            LoginState,
            LoginState,
            LoginState.AwaitingCreateConfirmation,
            LoginState.AwaitingExistingPassword,
            LoginState.AwaitingNewPassword,
            LoginState.AwaitingRaceSelection,
            LoginState.AwaitingClassSelection,
        >(
            onAwaitingName = loginFlowHandler::handleLoginName,
            onAwaitingCreateConfirmation = loginFlowHandler::handleLoginCreateConfirmation,
            onAwaitingExistingPassword = loginFlowHandler::handleLoginExistingPassword,
            onAwaitingNewPassword = loginFlowHandler::handleLoginNewPassword,
            onAwaitingRaceSelection = loginFlowHandler::handleLoginRaceSelection,
            onAwaitingClassSelection = loginFlowHandler::handleLoginClassSelection,
            asAwaitingCreateConfirmation = { state -> state as? LoginState.AwaitingCreateConfirmation },
            asAwaitingExistingPassword = { state -> state as? LoginState.AwaitingExistingPassword },
            asAwaitingNewPassword = { state -> state as? LoginState.AwaitingNewPassword },
            asAwaitingRaceSelection = { state -> state as? LoginState.AwaitingRaceSelection },
            asAwaitingClassSelection = { state -> state as? LoginState.AwaitingClassSelection },
            isAwaitingName = { state -> state == LoginState.AwaitingName },
            metrics = metrics,
        )
    }

    private val phaseEventHandler by lazy {
        PhaseEventHandler(
            handoffManager = handoffManager,
            zoneRegistry = zoneRegistry,
            players = players,
            combatSystem = combatSystem,
            regenSystem = regenSystem,
            statusEffectSystem = statusEffectSystem,
            playerLocationIndex = playerLocationIndex,
            engineId = engineId,
            sendInfo = { sid, msg -> outbound.send(OutboundEvent.SendInfo(sid, msg)) },
            sendPrompt = { sid -> outbound.send(OutboundEvent.SendPrompt(sid)) },
            logger = log,
            metrics = metrics,
        )
    }

    private val gmcpEventHandler by lazy {
        GmcpEventHandler(
            gmcpSessions = gmcpSessions,
            players = players,
            world = world,
            items = items,
            mobs = mobs,
            abilitySystem = abilitySystem,
            statusEffectSystem = statusEffectSystem,
            achievementRegistry = achievementRegistry,
            groupSystem = groupSystem,
            gmcpEmitter = gmcpEmitter,
            logger = log,
            metrics = metrics,
        )
    }

    private val gmcpFlushHandler by lazy {
        GmcpFlushHandler(
            gmcpDirtyVitals = gmcpDirtyVitals,
            gmcpDirtyStatusEffects = gmcpDirtyStatusEffects,
            gmcpDirtyMobs = gmcpDirtyMobs,
            gmcpDirtyGroup = gmcpDirtyGroup,
            players = players,
            mobs = mobs,
            statusEffectSystem = statusEffectSystem,
            groupSystem = groupSystem,
            gmcpEmitter = gmcpEmitter,
            metrics = metrics,
        )
    }

    private val interEngineEventHandler by lazy {
        InterEngineEventHandler(
            handoffManager = handoffManager,
            playerLocationIndex = playerLocationIndex,
            players = players,
            router = router,
            outbound = outbound,
            engineId = engineId,
            interEngineBus = interEngineBus,
            onShutdown = onShutdown,
            world = world,
            combatSystem = combatSystem,
            regenSystem = regenSystem,
            statusEffectSystem = statusEffectSystem,
            resolveRoomId = ::resolveRoomId,
            clock = clock,
            peerEngineCount = peerEngineCount,
            logger = log,
            metrics = metrics,
        )
    }

    private val eventDispatcher: EngineEventDispatcher =
        DefaultEngineEventDispatcher(
            onConnected = ::handleConnected,
            onGmcpReceived = ::handleGmcpReceived,
            onDisconnected = ::handleDisconnected,
            onLineReceived = ::handleLineReceived,
        )

    private val zoneResetHandler by lazy {
        ZoneResetHandler(
            world = world,
            mobs = mobs,
            items = items,
            players = players,
            outbound = outbound,
            worldState = worldState,
            combatSystem = combatSystem,
            dialogueSystem = dialogueSystem,
            behaviorTreeSystem = behaviorTreeSystem,
            mobSystem = mobSystem,
            gmcpEmitter = gmcpEmitter,
            clock = clock,
        )
    }

    /** GMCP packages each session has opted into (e.g. "Char.Vitals", "Room.Info"). */
    private val gmcpSessions = mutableMapOf<SessionId, MutableSet<String>>()

    /** Sessions whose vitals changed this tick and need a Char.Vitals push. */
    private val gmcpDirtyVitals = mutableSetOf<SessionId>()

    /** Mobs whose HP changed this tick and need a Room.UpdateMob push. */
    private val gmcpDirtyMobs = mutableSetOf<MobId>()

    /** Sessions whose status effects changed this tick and need a Char.StatusEffects push. */
    private val gmcpDirtyStatusEffects = mutableSetOf<SessionId>()

    /** Sessions whose group membership changed this tick and need a Group.Info push. */
    private val gmcpDirtyGroup = mutableSetOf<SessionId>()

    val gmcpEmitter =
        GmcpEmitter(
            outbound = outbound,
            supportsPackage = { sid, pkg ->
                gmcpSessions[sid]?.any { supported ->
                    pkg == supported || pkg.startsWith("$supported.")
                } == true
            },
            progression = progression,
            isInCombat = { sid -> combatSystem.isInCombat(sid) },
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

    private val dirtyNotifier =
        object : DirtyNotifier {
            override fun playerVitalsDirty(sessionId: SessionId) = markVitalsDirty(sessionId)

            override fun playerStatusDirty(sessionId: SessionId) = markStatusDirty(sessionId)

            override fun mobHpDirty(mobId: MobId) = markMobHpDirty(mobId)
        }

    fun markGroupDirty(sessionId: SessionId) {
        gmcpDirtyGroup.add(sessionId)
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
            dirtyNotifier = dirtyNotifier,
        )
    private val groupSystem =
        GroupSystem(
            players = players,
            outbound = outbound,
            clock = clock,
            maxGroupSize = engineConfig.group.maxSize,
            inviteTimeoutMs = engineConfig.group.inviteTimeoutMs,
            markGroupDirty = ::markGroupDirty,
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
            onMobRemoved = ::onCombatMobRemoved,
            progression = progression,
            metrics = metrics,
            strDivisor = engineConfig.combat.strDivisor,
            dexDodgePerPoint = engineConfig.combat.dexDodgePerPoint,
            maxDodgePercent = engineConfig.combat.maxDodgePercent,
            dirtyNotifier = dirtyNotifier,
            statusEffects = statusEffectSystem,
            groupSystem = groupSystem,
            groupXpBonusPerMember = engineConfig.group.xpBonusPerMember,
            onLevelUp = ::onCombatLevelUp,
            onMobKilledByPlayer = ::onCombatMobKilledByPlayer,
            onRoomItemsChanged = ::syncRoomItemsForRoom,
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
            dirtyNotifier = dirtyNotifier,
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
            dirtyNotifier = dirtyNotifier,
            statusEffects = statusEffectSystem,
            groupSystem = groupSystem,
            mobs = mobs,
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

    private val router = CommandRouter(outbound = outbound, players = players)

    init {
        val crossZoneMove: (suspend (SessionId, RoomId) -> Unit)? = if (handoffManager != null) ::handleCrossZoneMove else null
        val phaseCallback: (suspend (SessionId, String?) -> PhaseResult)? =
            if (zoneRegistry != null && zoneRegistry.instancingEnabled() && handoffManager != null) {
                ::handlePhase
            } else {
                null
            }

        val ctx = EngineContext(
            players = players,
            mobs = mobs,
            world = world,
            items = items,
            outbound = outbound,
            combat = combatSystem,
            gmcpEmitter = gmcpEmitter,
            worldState = worldState,
        )

        listOf(
            NavigationHandler(
                ctx = ctx,
                statusEffects = statusEffectSystem,
                dialogueSystem = dialogueSystem,
                onCrossZoneMove = crossZoneMove,
            ),
            CommunicationHandler(
                ctx = ctx,
                groupSystem = groupSystem,
                interEngineBus = interEngineBus,
                playerLocationIndex = playerLocationIndex,
                engineId = engineId,
                onRemoteWho = if (interEngineBus != null) interEngineEventHandler::handleRemoteWho else null,
            ),
            CombatHandler(
                ctx = ctx,
                abilitySystem = abilitySystem,
                statusEffects = statusEffectSystem,
                dialogueSystem = dialogueSystem,
            ),
            ProgressionHandler(
                ctx = ctx,
                progression = progression,
                abilitySystem = abilitySystem,
                statusEffects = statusEffectSystem,
                groupSystem = groupSystem,
            ),
            ItemHandler(
                ctx = ctx,
                questSystem = questSystem,
                abilitySystem = abilitySystem,
                markVitalsDirty = ::markVitalsDirty,
                metrics = metrics,
                progression = progression,
            ),
            ShopHandler(
                ctx = ctx,
                shopRegistry = shopRegistry,
                markVitalsDirty = ::markVitalsDirty,
                economyConfig = engineConfig.economy,
            ),
            DialogueQuestHandler(
                ctx = ctx,
                dialogueSystem = dialogueSystem,
                questSystem = questSystem,
                questRegistry = questRegistry,
                achievementSystem = achievementSystem,
                achievementRegistry = achievementRegistry,
            ),
            GroupHandler(
                ctx = ctx,
                groupSystem = groupSystem,
            ),
            WorldFeaturesHandler(ctx = ctx),
            AdminHandler(
                ctx = ctx,
                onShutdown = onShutdown,
                onWorldReimport = onWorldReimport,
                onMobSmited = mobSystem::onMobRemoved,
                onCrossZoneMove = crossZoneMove,
                dialogueSystem = dialogueSystem,
                statusEffects = statusEffectSystem,
                interEngineBus = interEngineBus,
                engineId = engineId,
                metrics = metrics,
            ),
            UiHandler(
                ctx = ctx,
                onPhase = phaseCallback,
            ),
        ).forEach { it.register(router) }
    }

    /**
     * Coroutine scope provided by [run]; used to launch background auth coroutines
     * without blocking the engine tick loop.
     */
    private lateinit var engineScope: CoroutineScope

    init {
        world.mobSpawns.forEach { spawn ->
            mobs.upsert(spawnToMobState(spawn))
        }
        items.loadSpawns(world.itemSpawns)
        shopRegistry.register(world.shopDefinitions)
        // Seed container initial items from feature definitions (snapshot may override below in run()).
        for (room in world.rooms.values) {
            for (feature in room.features.filterIsInstance<RoomFeature.Container>()) {
                val instances = feature.initialItems.mapNotNull { items.createFromTemplate(it) }
                if (instances.isNotEmpty()) {
                    for (inst in instances) worldState.addToContainer(feature.id, inst)
                }
            }
        }
        worldState.clearDirty()
    }

    suspend fun run() =
        coroutineScope {
            engineScope = this

            // Restore persisted world state, overriding in-memory defaults.
            worldStateRepository?.load()?.let { snapshot ->
                worldState.applySnapshot(snapshot) { itemId -> items.createFromTemplate(itemId) }
            }

            var tickDebtMs = 0L
            while (isActive) {
                val tickStart = clock.millis()
                val tickSample = Timer.start()

                try {
                    // Phase 1: Drain inbound events with a time budget to leave room for simulation,
                    // interleaving auth-result processing so a session whose async auth just
                    // completed is in the correct state before its next queued input is handled.
                    val inboundPhaseSample = Timer.start()
                    var inboundProcessed = 0
                    val inboundDeadline = tickStart + inboundBudgetMs
                    while (inboundProcessed < maxInboundEventsPerTick) {
                        if (clock.millis() >= inboundDeadline) {
                            metrics.onInboundDrainBudgetExceeded()
                            break
                        }
                        // Drain any auth results that have arrived since the last event.
                        loginFlowHandler.drainPendingAuthResults()
                        val ev = inbound.tryReceive().getOrNull() ?: break
                        metrics.recordInboundLatency(clock.millis() - ev.enqueuedAt)
                        eventDispatcher.dispatch(ev)
                        // Yield so that launched auth coroutines (BCrypt / DB) can post
                        // their results before we process the next event for this session.
                        yield()
                        inboundProcessed++
                    }
                    // Final drain: pick up results from the last event or from auth
                    // operations that completed between ticks.
                    loginFlowHandler.drainPendingAuthResults()
                    metrics.onInboundEventsProcessed(inboundProcessed)

                    // Drain inter-engine messages (cross-zone handoffs, global commands)
                    if (interEngineBus != null) {
                        var interEngineProcessed = 0
                        while (interEngineProcessed < maxInboundEventsPerTick) {
                            if (clock.millis() >= inboundDeadline) break
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
                    interEngineEventHandler.flushDueWhoResponses()
                    metrics.recordTickPhase("inbound_drain", inboundPhaseSample)

                    // Phase 2: Simulation — mob movement, behavior, combat, status effects, regen.
                    val simulationPhaseSample = Timer.start()
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
                    metrics.recordTickPhase("simulation", simulationPhaseSample)

                    // Phase 3: Flush GMCP vitals for sessions that had changes this tick.
                    val gmcpFlushPhaseSample = Timer.start()
                    flushDirtyGmcpVitals()
                    flushDirtyGmcpMobs()
                    flushDirtyGmcpStatusEffects()
                    flushDirtyGmcpGroup()
                    metrics.recordTickPhase("gmcp_flush", gmcpFlushPhaseSample)

                    // Phase 4: Outbound flush — run scheduled actions and reset expired zones.
                    val outboundFlushPhaseSample = Timer.start()
                    val schedulerSample = Timer.start()
                    val (actionsRan, actionsDropped) = scheduler.runDue(maxActions = engineConfig.scheduler.maxActionsPerTick)
                    schedulerSample.stop(metrics.schedulerRunDueTimer)
                    metrics.onSchedulerActionsExecuted(actionsRan)
                    metrics.onSchedulerActionsDropped(actionsDropped)

                    // Reset zones when their lifespan elapses.
                    zoneResetHandler.tick()
                    metrics.recordTickPhase("outbound_flush", outboundFlushPhaseSample)
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    log.error(t) { "Unhandled exception during tick processing" }
                }

                val elapsed = clock.millis() - tickStart
                val sleep = (tickMillis - elapsed).coerceAtLeast(0)
                tickDebtMs = maxOf(0L, tickDebtMs + elapsed - tickMillis)

                metrics.onEngineTick()
                metrics.updateTickDebt(tickDebtMs)
                if (elapsed > tickMillis) metrics.onEngineTickOverrun(inbound.depth())
                if (elapsed > tickMillis * 2) log.warn { "Slow tick: elapsed=${elapsed}ms (threshold=${tickMillis * 2}ms)" }
                tickSample.stop(metrics.engineTickTimer)

                delay(sleep)
            }
        }

    private suspend fun handleCrossZoneMove(
        sessionId: SessionId,
        targetRoomId: RoomId,
    ) {
        phaseEventHandler.handleCrossZoneMove(sessionId, targetRoomId)
    }

    private suspend fun handlePhase(
        sessionId: SessionId,
        targetHint: String?,
    ): PhaseResult =
        phaseEventHandler.handlePhase(sessionId, targetHint)

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
        interEngineEventHandler.onInterEngineMessage(msg)
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

    private suspend fun handleConnected(
        sessionId: SessionId,
        defaultAnsiEnabled: Boolean,
    ) {
        sessionEventHandler.onConnected(sessionId, defaultAnsiEnabled)
    }

    private suspend fun handleDisconnected(sessionId: SessionId) {
        sessionEventHandler.onDisconnected(sessionId)
    }

    private suspend fun handleLineReceived(
        sessionId: SessionId,
        line: String,
    ) {
        inputEventHandler.onLineReceived(sessionId, line)
    }

    private suspend fun handleLoginLine(
        sessionId: SessionId,
        line: String,
        state: LoginState,
    ) {
        loginEventHandler.onLoginLine(sessionId, line, state)
    }

    private suspend fun handleGmcpReceived(ev: InboundEvent.GmcpReceived) {
        gmcpEventHandler.onGmcpReceived(ev)
    }

    private suspend fun flushDirtyGmcpVitals() {
        gmcpFlushHandler.flushDirtyVitals()
    }

    private suspend fun flushDirtyGmcpStatusEffects() {
        gmcpFlushHandler.flushDirtyStatusEffects()
    }

    private suspend fun flushDirtyGmcpMobs() {
        gmcpFlushHandler.flushDirtyMobs()
    }

    private suspend fun flushDirtyGmcpGroup() {
        gmcpFlushHandler.flushDirtyGroup()
    }

    private suspend fun onCombatMobRemoved(
        mobId: MobId,
        roomId: RoomId,
    ) {
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
    }

    private suspend fun onCombatLevelUp(
        sessionId: SessionId,
        level: Int,
    ) {
        markVitalsDirty(sessionId)
        val pc = players.get(sessionId)?.playerClass
        val newAbilities = abilitySystem.syncAbilities(sessionId, level, pc)
        for (ability in newAbilities) {
            outbound.send(OutboundEvent.SendText(sessionId, "You have learned ${ability.displayName}!"))
        }
        val p = players.get(sessionId)
        if (p != null) {
            gmcpEmitter.sendCharName(sessionId, p)
            gmcpEmitter.sendCharSkills(sessionId, abilitySystem.knownAbilities(sessionId)) { abilityId ->
                abilitySystem.cooldownRemainingMs(sessionId, abilityId)
            }
        }
        achievementSystem.onLevelReached(sessionId, level)
    }

    private suspend fun syncRoomItemsForRoom(roomId: RoomId) {
        val roomItems = items.itemsInRoom(roomId)
        for (player in players.playersInRoom(roomId)) {
            gmcpEmitter.sendRoomItems(player.sessionId, roomItems)
        }
    }

    private suspend fun onCombatMobKilledByPlayer(
        sessionId: SessionId,
        templateKey: String,
    ) {
        questSystem.onMobKilled(sessionId, templateKey)
        achievementSystem.onMobKilled(sessionId, templateKey)
    }
}
