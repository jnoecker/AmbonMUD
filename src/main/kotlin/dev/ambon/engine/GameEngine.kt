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
import dev.ambon.domain.world.RoomFeature
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
import dev.ambon.engine.commands.handlers.AdminHandler
import dev.ambon.engine.commands.handlers.CombatHandler
import dev.ambon.engine.commands.handlers.CommunicationHandler
import dev.ambon.engine.commands.handlers.DialogueQuestHandler
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
import dev.ambon.sharding.HandoffResult
import dev.ambon.sharding.InterEngineBus
import dev.ambon.sharding.InterEngineMessage
import dev.ambon.sharding.PlayerLocationIndex
import dev.ambon.sharding.PlayerSummary
import dev.ambon.sharding.ZoneRegistry
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.Timer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
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
    private val inboundBudgetMs: Long = 30L,
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
    /** Mutable world feature state (doors, containers, levers). Caller owns and must pass the same instance to the persistence worker. */
    private val worldState: WorldStateRegistry = WorldStateRegistry(world),
    /** Repository for persisting world feature states across restarts. */
    private val worldStateRepository: WorldStateRepository? = null,
) {
    private val sessionEventHandler by lazy {
        SessionEventHandler(
            players = players,
            markAwaitingName = { sid -> pendingLogins[sid] = LoginState.AwaitingName },
            clearLoginState = { sid -> pendingLogins.remove(sid) },
            failedLoginAttempts = failedLoginAttempts,
            sessionAnsiDefaults = sessionAnsiDefaults,
            gmcpSessions = gmcpSessions,
            gmcpDirtyVitals = gmcpDirtyVitals,
            gmcpDirtyStatusEffects = gmcpDirtyStatusEffects,
            gmcpDirtyGroup = gmcpDirtyGroup,
            handoffManager = handoffManager,
            removePendingWhoRequestsFor = { sid ->
                val itr = pendingWhoRequests.iterator()
                while (itr.hasNext()) {
                    if (itr.next().value.sessionId == sid) {
                        itr.remove()
                    }
                }
            },
            combatSystem = combatSystem,
            regenSystem = regenSystem,
            abilitySystem = abilitySystem,
            statusEffectSystem = statusEffectSystem,
            dialogueSystem = dialogueSystem,
            groupSystem = groupSystem,
            promptForName = ::promptForName,
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
            getLoginState = { sid -> pendingLogins[sid] },
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
            onAwaitingName = ::handleLoginName,
            onAwaitingCreateConfirmation = ::handleLoginCreateConfirmation,
            onAwaitingExistingPassword = ::handleLoginExistingPassword,
            onAwaitingNewPassword = ::handleLoginNewPassword,
            onAwaitingRaceSelection = ::handleLoginRaceSelection,
            onAwaitingClassSelection = ::handleLoginClassSelection,
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
            onWhoResponse = { response ->
                val pending = pendingWhoRequests[response.requestId] ?: return@InterEngineEventHandler
                if (players.get(pending.sessionId) == null) {
                    pendingWhoRequests.remove(response.requestId)
                    return@InterEngineEventHandler
                }
                pending.respondedCount++
                pending.remotePlayerNames += response.players.map(PlayerSummary::name)
            },
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
            markVitalsDirty = ::markVitalsDirty,
            markMobHpDirty = ::markMobHpDirty,
            markStatusDirty = ::markStatusDirty,
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
            groupSystem = groupSystem,
            groupXpBonusPerMember = engineConfig.group.xpBonusPerMember,
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

    private val router = CommandRouter()

    init {
        val crossZoneMove: (suspend (SessionId, RoomId) -> Unit)? = if (handoffManager != null) ::handleCrossZoneMove else null
        val phaseCallback: (suspend (SessionId, String?) -> PhaseResult)? =
            if (zoneRegistry != null && zoneRegistry.instancingEnabled() && handoffManager != null) {
                ::handlePhase
            } else {
                null
            }

        NavigationHandler(
            router = router,
            world = world,
            players = players,
            mobs = mobs,
            items = items,
            combat = combatSystem,
            outbound = outbound,
            worldState = worldState,
            gmcpEmitter = gmcpEmitter,
            statusEffects = statusEffectSystem,
            dialogueSystem = dialogueSystem,
            onCrossZoneMove = crossZoneMove,
        )
        CommunicationHandler(
            router = router,
            players = players,
            outbound = outbound,
            gmcpEmitter = gmcpEmitter,
            groupSystem = groupSystem,
            interEngineBus = interEngineBus,
            playerLocationIndex = playerLocationIndex,
            engineId = engineId,
            onRemoteWho = if (interEngineBus != null) ::handleRemoteWho else null,
        )
        CombatHandler(
            router = router,
            players = players,
            mobs = mobs,
            combat = combatSystem,
            outbound = outbound,
            abilitySystem = abilitySystem,
            statusEffects = statusEffectSystem,
            dialogueSystem = dialogueSystem,
        )
        ProgressionHandler(
            router = router,
            players = players,
            items = items,
            combat = combatSystem,
            outbound = outbound,
            progression = progression,
            abilitySystem = abilitySystem,
            statusEffects = statusEffectSystem,
            groupSystem = groupSystem,
        )
        ItemHandler(
            router = router,
            players = players,
            items = items,
            combat = combatSystem,
            outbound = outbound,
            gmcpEmitter = gmcpEmitter,
            questSystem = questSystem,
            abilitySystem = abilitySystem,
            markVitalsDirty = ::markVitalsDirty,
            metrics = metrics,
            progression = progression,
        )
        ShopHandler(
            router = router,
            players = players,
            items = items,
            outbound = outbound,
            shopRegistry = shopRegistry,
            gmcpEmitter = gmcpEmitter,
            markVitalsDirty = ::markVitalsDirty,
            economyConfig = engineConfig.economy,
        )
        DialogueQuestHandler(
            router = router,
            players = players,
            mobs = mobs,
            outbound = outbound,
            dialogueSystem = dialogueSystem,
            questSystem = questSystem,
            questRegistry = questRegistry,
            achievementSystem = achievementSystem,
            achievementRegistry = achievementRegistry,
        )
        GroupHandler(
            router = router,
            outbound = outbound,
            groupSystem = groupSystem,
        )
        WorldFeaturesHandler(
            router = router,
            world = world,
            players = players,
            items = items,
            outbound = outbound,
            worldState = worldState,
        )
        AdminHandler(
            router = router,
            world = world,
            players = players,
            mobs = mobs,
            items = items,
            combat = combatSystem,
            outbound = outbound,
            onShutdown = onShutdown,
            onMobSmited = mobSystem::onMobRemoved,
            onCrossZoneMove = crossZoneMove,
            dialogueSystem = dialogueSystem,
            gmcpEmitter = gmcpEmitter,
            statusEffects = statusEffectSystem,
            interEngineBus = interEngineBus,
            engineId = engineId,
            metrics = metrics,
            worldState = worldState,
        )
        UiHandler(
            router = router,
            players = players,
            outbound = outbound,
            combat = combatSystem,
            onPhase = phaseCallback,
        )
    }

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

    /**
     * Channel for auth results produced by background coroutines.
     * Results are drained on the engine thread at the start of each event-processing
     * iteration so auth completions unblock the next queued input for the same session
     * within the same tick.
     */
    private val pendingAuthResults = Channel<PendingAuthResult>(Channel.UNLIMITED)

    /**
     * Coroutine scope provided by [run]; used to launch background auth coroutines
     * without blocking the engine tick loop.
     */
    private lateinit var engineScope: CoroutineScope

    /** Result posted to [pendingAuthResults] by a background auth coroutine. */
    private sealed interface PendingAuthResult {
        val sessionId: SessionId

        /** DB name-existence check finished. */
        data class NameLookup(
            override val sessionId: SessionId,
            val name: String,
            val exists: Boolean,
        ) : PendingAuthResult

        /** Password verification (BCrypt + DB) finished. */
        data class PasswordAuth(
            override val sessionId: SessionId,
            val prep: LoginCredentialPrep,
            val wrongPasswordAttempts: Int,
            val defaultAnsiEnabled: Boolean,
        ) : PendingAuthResult

        /** New-account creation (hash + DB write) finished. */
        data class NewAccountAuth(
            override val sessionId: SessionId,
            val prep: CreateAccountPrep,
        ) : PendingAuthResult
    }

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

        /** Name-existence check in-flight; player input is buffered until complete. */
        data class AwaitingNameLookup(
            val name: String,
        ) : LoginState

        /** BCrypt password verification in-flight; player input is ignored until complete. */
        data class AwaitingPasswordAuth(
            val name: String,
            val wrongPasswordAttempts: Int = 0,
        ) : LoginState

        /** Account creation (hash + DB) in-flight; player input is ignored until complete. */
        data class AwaitingCreateAuth(
            val name: String,
            val password: String,
            val race: Race,
            val playerClass: PlayerClass,
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
                        drainPendingAuthResults()
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
                    drainPendingAuthResults()
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
                    flushDueWhoResponses()
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
                    resetZonesIfDue()
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

    private suspend fun resetZonesIfDue() {
        if (zoneResetDueAtMillis.isEmpty()) return

        val now = clock.millis()
        for ((zone, dueAtMillis) in zoneResetDueAtMillis) {
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

        // Reset stateful room features (doors, containers, levers) for this zone.
        worldState.resetZone(zone)
        for (room in world.rooms.values.filter { it.id.zone == zone }) {
            for (feature in room.features.filterIsInstance<RoomFeature.Container>()) {
                if (!feature.resetWithZone) continue
                val instances = feature.initialItems.mapNotNull { items.createFromTemplate(it) }
                worldState.resetContainer(feature.id, instances)
            }
        }

        // Refresh mob GMCP for all players in the reset zone
        for (player in playersInZone) {
            gmcpEmitter.sendRoomMobs(player.sessionId, mobs.mobsInRoom(player.roomId))
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

        // Kick off the DB lookup on a background coroutine so the engine tick loop
        // is not blocked while waiting for the repository.
        pendingLogins[sessionId] = LoginState.AwaitingNameLookup(name)
        engineScope.launch {
            val exists = players.hasRegisteredName(name)
            pendingAuthResults.send(PendingAuthResult.NameLookup(sessionId, name, exists))
        }
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

        // Capture ANSI preference on the engine thread before handing off to background.
        val ansiDefault = sessionAnsiDefaults[sessionId] ?: false
        pendingLogins[sessionId] = LoginState.AwaitingPasswordAuth(name, state.wrongPasswordAttempts)
        engineScope.launch {
            val prep = players.prepareLoginCredentials(name, password)
            pendingAuthResults.send(
                PendingAuthResult.PasswordAuth(sessionId, prep, state.wrongPasswordAttempts, ansiDefault),
            )
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

        // Capture ANSI preference on the engine thread before handing off to background.
        val ansiDefault = sessionAnsiDefaults[sessionId] ?: false
        pendingLogins[sessionId] = LoginState.AwaitingCreateAuth(state.name, state.password, state.race, playerClass)
        engineScope.launch {
            val prep = players.prepareCreateAccount(state.name, state.password, ansiDefault, state.race, playerClass)
            pendingAuthResults.send(PendingAuthResult.NewAccountAuth(sessionId, prep))
        }
    }

    /** Drains all results from [pendingAuthResults] that are immediately available. */
    private suspend fun drainPendingAuthResults() {
        while (true) {
            val result = pendingAuthResults.tryReceive().getOrNull() ?: break
            handlePendingAuthResult(result)
        }
    }

    /** Processes a single auth result produced by a background coroutine. */
    private suspend fun handlePendingAuthResult(result: PendingAuthResult) {
        val sid = result.sessionId
        when (result) {
            is PendingAuthResult.NameLookup -> {
                // Ignore stale results (session disconnected or re-used).
                if (pendingLogins[sid] !is LoginState.AwaitingNameLookup) return
                if (result.exists) {
                    pendingLogins[sid] = LoginState.AwaitingExistingPassword(result.name)
                    promptForExistingPassword(sid)
                } else {
                    pendingLogins[sid] = LoginState.AwaitingCreateConfirmation(result.name)
                    promptForCreateConfirmation(sid, result.name)
                }
            }

            is PendingAuthResult.PasswordAuth -> {
                val currentState = pendingLogins[sid] as? LoginState.AwaitingPasswordAuth ?: return
                when (result.prep) {
                    is LoginCredentialPrep.Verified -> {
                        when (val loginResult = players.applyLoginCredentials(sid, result.prep.record, result.defaultAnsiEnabled)) {
                            LoginResult.Ok -> finalizeSuccessfulLogin(sid)
                            is LoginResult.Takeover -> {
                                val oldSid = loginResult.oldSessionId
                                combatSystem.remapSession(oldSid, sid)
                                regenSystem.remapSession(oldSid, sid)
                                abilitySystem.remapSession(oldSid, sid)
                                statusEffectSystem.remapSession(oldSid, sid)
                                groupSystem.remapSession(oldSid, sid)
                                outbound.send(OutboundEvent.Close(oldSid, "Your account has logged in from another location."))
                                val me = players.get(sid)
                                if (me != null) broadcastToRoom(players, outbound, me.roomId, "${me.name} briefly flickers.", sid)
                                finalizeSuccessfulLogin(sid, suppressEnterBroadcast = true)
                            }
                            LoginResult.InvalidName -> {
                                outbound.send(OutboundEvent.SendError(sid, invalidNameMessage))
                                if (recordFailedLoginAttemptAndCloseIfNeeded(sid)) return
                                pendingLogins[sid] = LoginState.AwaitingName
                                promptForName(sid)
                            }
                            LoginResult.InvalidPassword -> {
                                outbound.send(OutboundEvent.SendError(sid, invalidPasswordMessage))
                                if (recordFailedLoginAttemptAndCloseIfNeeded(sid)) return
                                pendingLogins[sid] = LoginState.AwaitingName
                                promptForName(sid)
                            }
                            LoginResult.Taken -> {
                                outbound.send(OutboundEvent.SendError(sid, "That name is already taken."))
                                pendingLogins[sid] = LoginState.AwaitingName
                                promptForName(sid)
                            }
                            LoginResult.WrongPassword -> {
                                // Should not happen: WrongPassword is handled by LoginCredentialPrep below.
                            }
                        }
                    }
                    LoginCredentialPrep.WrongPassword -> {
                        val attempts = currentState.wrongPasswordAttempts + 1
                        if (attempts > maxWrongPasswordRetries) {
                            outbound.send(OutboundEvent.SendError(sid, "Incorrect password too many times. Returning to login."))
                            if (recordFailedLoginAttemptAndCloseIfNeeded(sid)) return
                            pendingLogins[sid] = LoginState.AwaitingName
                            promptForName(sid)
                            return
                        }
                        val attemptsRemaining = (maxWrongPasswordRetries + 1) - attempts
                        outbound.send(
                            OutboundEvent.SendError(
                                sid,
                                "Incorrect password. $attemptsRemaining attempt(s) before returning to login.",
                            ),
                        )
                        pendingLogins[sid] = LoginState.AwaitingExistingPassword(currentState.name, attempts)
                        promptForExistingPassword(sid)
                    }
                    LoginCredentialPrep.NotFound -> {
                        // Account was removed between name-check and password entry.
                        outbound.send(OutboundEvent.SendError(sid, "Account not found. Please try again."))
                        if (recordFailedLoginAttemptAndCloseIfNeeded(sid)) return
                        pendingLogins[sid] = LoginState.AwaitingName
                        promptForName(sid)
                    }
                    LoginCredentialPrep.InvalidInput -> {
                        outbound.send(OutboundEvent.SendError(sid, invalidNameMessage))
                        if (recordFailedLoginAttemptAndCloseIfNeeded(sid)) return
                        pendingLogins[sid] = LoginState.AwaitingName
                        promptForName(sid)
                    }
                }
            }

            is PendingAuthResult.NewAccountAuth -> {
                if (pendingLogins[sid] !is LoginState.AwaitingCreateAuth) return
                val createState = pendingLogins[sid] as LoginState.AwaitingCreateAuth
                when (result.prep) {
                    is CreateAccountPrep.Ready -> {
                        when (players.applyCreateAccount(sid, result.prep.record)) {
                            CreateResult.Ok -> finalizeSuccessfulLogin(sid)
                            CreateResult.InvalidName -> {
                                outbound.send(OutboundEvent.SendError(sid, invalidNameMessage))
                                if (recordFailedLoginAttemptAndCloseIfNeeded(sid)) return
                                pendingLogins[sid] = LoginState.AwaitingName
                                promptForName(sid)
                            }
                            CreateResult.InvalidPassword -> {
                                outbound.send(OutboundEvent.SendError(sid, invalidPasswordMessage))
                                pendingLogins[sid] = LoginState.AwaitingNewPassword(createState.name)
                                promptForNewPassword(sid)
                            }
                            CreateResult.Taken -> {
                                outbound.send(OutboundEvent.SendError(sid, "That name is already taken."))
                                if (recordFailedLoginAttemptAndCloseIfNeeded(sid)) return
                                pendingLogins[sid] = LoginState.AwaitingName
                                promptForName(sid)
                            }
                        }
                    }
                    CreateAccountPrep.Taken -> {
                        outbound.send(OutboundEvent.SendError(sid, "That name is already taken."))
                        if (recordFailedLoginAttemptAndCloseIfNeeded(sid)) return
                        pendingLogins[sid] = LoginState.AwaitingName
                        promptForName(sid)
                    }
                    CreateAccountPrep.InvalidInput -> {
                        outbound.send(OutboundEvent.SendError(sid, invalidNameMessage))
                        if (recordFailedLoginAttemptAndCloseIfNeeded(sid)) return
                        pendingLogins[sid] = LoginState.AwaitingName
                        promptForName(sid)
                    }
                }
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
        val group = groupSystem.getGroup(sessionId)
        if (group != null) {
            val leader = players.get(group.leader)?.name
            val members = group.members.mapNotNull { players.get(it) }
            gmcpEmitter.sendGroupInfo(sessionId, leader, members)
        }
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
