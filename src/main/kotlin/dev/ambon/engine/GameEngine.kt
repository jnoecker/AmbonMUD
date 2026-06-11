package dev.ambon.engine

import dev.ambon.bus.InboundBus
import dev.ambon.bus.OutboundBus
import dev.ambon.config.EngineConfig
import dev.ambon.config.LoginConfig
import dev.ambon.domain.dungeon.DungeonDifficulty
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.LockableState
import dev.ambon.domain.world.RoomFeature
import dev.ambon.domain.world.World
import dev.ambon.domain.world.opposite
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
import dev.ambon.engine.commands.handlers.AkathavaeHandler
import dev.ambon.engine.commands.handlers.AuctionHandler
import dev.ambon.engine.commands.handlers.AutoQuestHandler
import dev.ambon.engine.commands.handlers.BankHandler
import dev.ambon.engine.commands.handlers.ClaimHandler
import dev.ambon.engine.commands.handlers.CombatHandler
import dev.ambon.engine.commands.handlers.CommunicationHandler
import dev.ambon.engine.commands.handlers.CraftingHandler
import dev.ambon.engine.commands.handlers.CurrencyHandler
import dev.ambon.engine.commands.handlers.DailyQuestHandler
import dev.ambon.engine.commands.handlers.DialogueQuestHandler
import dev.ambon.engine.commands.handlers.DuelHandler
import dev.ambon.engine.commands.handlers.DungeonHandler
import dev.ambon.engine.commands.handlers.EnchantHandler
import dev.ambon.engine.commands.handlers.EngineContext
import dev.ambon.engine.commands.handlers.FriendsHandler
import dev.ambon.engine.commands.handlers.GlobalQuestHandler
import dev.ambon.engine.commands.handlers.GroupHandler
import dev.ambon.engine.commands.handlers.GuildHandler
import dev.ambon.engine.commands.handlers.HousingHandler
import dev.ambon.engine.commands.handlers.ItemHandler
import dev.ambon.engine.commands.handlers.LeaderboardHandler
import dev.ambon.engine.commands.handlers.LotteryHandler
import dev.ambon.engine.commands.handlers.MailHandler
import dev.ambon.engine.commands.handlers.NavigationHandler
import dev.ambon.engine.commands.handlers.PetHandler
import dev.ambon.engine.commands.handlers.PrestigeHandler
import dev.ambon.engine.commands.handlers.ProgressionHandler
import dev.ambon.engine.commands.handlers.PuzzleHandler
import dev.ambon.engine.commands.handlers.ReputationHandler
import dev.ambon.engine.commands.handlers.ShopHandler
import dev.ambon.engine.commands.handlers.SpriteHandler
import dev.ambon.engine.commands.handlers.StylistHandler
import dev.ambon.engine.commands.handlers.TradeHandler
import dev.ambon.engine.commands.handlers.TrainerHandler
import dev.ambon.engine.commands.handlers.UiHandler
import dev.ambon.engine.commands.handlers.WorldFeaturesHandler
import dev.ambon.engine.commands.handlers.WorldInfoHandler
import dev.ambon.engine.commands.handlers.movePlayerWithNotify
import dev.ambon.engine.commands.handlers.sendLook
import dev.ambon.engine.crafting.CraftingRegistry
import dev.ambon.engine.crafting.CraftingSystem
import dev.ambon.engine.crafting.EnchantSystem
import dev.ambon.engine.crafting.GatheringRegistry
import dev.ambon.engine.dialogue.DialogueSystem
import dev.ambon.engine.dungeon.DungeonManager
import dev.ambon.engine.dungeon.DungeonRegistry
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
import dev.ambon.engine.status.StatusEffectRegistry
import dev.ambon.engine.status.StatusEffectRegistryLoader
import dev.ambon.engine.status.StatusEffectSystem
import dev.ambon.metrics.GameMetrics
import dev.ambon.persistence.GuildRepository
import dev.ambon.persistence.HouseRepository
import dev.ambon.persistence.PlayerRepository
import dev.ambon.persistence.WorldStateRepository
import dev.ambon.sharding.HandoffManager
import dev.ambon.sharding.InterEngineBus
import dev.ambon.sharding.InterEngineMessage
import dev.ambon.sharding.PlayerLocationIndex
import dev.ambon.sharding.TimedOutHandoff
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

private const val AUTH_TOKEN_EXPIRY_DAYS = 365
private const val THREAT_CLEANUP_INTERVAL_MS = 60_000L

@OptIn(ExperimentalStdlibApi::class)
private fun sha256Hex(input: String): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray())
        .toHexString()

/**
 * Groups all sharding / multi-engine parameters. Pass a non-default instance only when running
 * in ENGINE or STANDALONE mode with an active shard topology; STANDALONE single-node setups
 * can omit this entirely and rely on the all-null defaults.
 */
data class ShardingContext(
    val engineId: String = "",
    val handoffManager: HandoffManager? = null,
    val interEngineBus: InterEngineBus? = null,
    /** Returns the number of peer engines (excluding self). Used for `who` shard-coverage warnings. */
    val peerEngineCount: () -> Int = { 0 },
    /** O(1) cross-engine tell / who routing. */
    val playerLocationIndex: PlayerLocationIndex? = null,
    /** Zone registry for instancing-aware phase command. */
    val zoneRegistry: ZoneRegistry? = null,
)

/**
 * Groups optional persistence repositories that the engine writes to at runtime.
 * All fields default to null; the engine degrades gracefully when a repo is absent.
 */
data class PersistenceContext(
    val worldStateRepository: WorldStateRepository? = null,
    val guildRepo: GuildRepository? = null,
    val playerRepo: PlayerRepository? = null,
    val houseRepo: HouseRepository? = null,
)

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
    /** Mutable world feature state (doors, containers, levers). Caller owns and must pass the same instance to the persistence worker. */
    private val worldState: WorldStateRegistry = WorldStateRegistry(world),
    private val questRegistry: QuestRegistry = QuestRegistry(),
    private val achievementRegistry: AchievementRegistry = AchievementRegistry(),
    private val sharding: ShardingContext = ShardingContext(),
    private val persistence: PersistenceContext = PersistenceContext(),
    private val abilityRegistry: AbilityRegistry = AbilityRegistry(),
    private val statusEffectRegistry: StatusEffectRegistry = StatusEffectRegistry(),
    private val shopRegistry: ShopRegistry = ShopRegistry(items),
    private val trainerRegistry: TrainerRegistry = TrainerRegistry(),
    classRegistryOverride: PlayerClassRegistry? = null,
    raceRegistryOverride: RaceRegistry? = null,
    statRegistryOverride: StatRegistry? = null,
    imagesBaseUrl: String = "/images/",
    private val voicesBaseUrl: String = "/voices/",
    private val voicesEnabled: Boolean = false,
    globalAssets: Map<String, String> = emptyMap(),
    private val spriteRegistry: SpriteRegistry? = null,
    private val worldLoader: (() -> World)? = null,
    private val reloadChannel: kotlinx.coroutines.channels.Channel<ReloadRequest>? = null,
) {
    // Convenience delegates — expose grouped context fields as flat names so the
    // existing class body compiles without modification.
    private val engineId get() = sharding.engineId
    private val handoffManager get() = sharding.handoffManager
    private val interEngineBus get() = sharding.interEngineBus
    private val peerEngineCount get() = sharding.peerEngineCount
    private val playerLocationIndex get() = sharding.playerLocationIndex
    private val zoneRegistry get() = sharding.zoneRegistry
    private val worldStateRepository get() = persistence.worldStateRepository
    private val guildRepo get() = persistence.guildRepo
    private val playerRepo get() = persistence.playerRepo
    private val houseRepo get() = persistence.houseRepo

    private val classRegistry = classRegistryOverride
        ?: PlayerClassRegistry().also { reg ->
            PlayerClassRegistryLoader.load(engineConfig.classes, reg)
        }
    private val raceRegistry = raceRegistryOverride
        ?: RaceRegistry().also { reg ->
            RaceRegistryLoader.load(engineConfig.races, reg)
        }
    private val statRegistry = statRegistryOverride
        ?: StatRegistry().also { reg ->
            StatRegistryLoader.load(engineConfig.stats, reg)
        }
    private val equipmentSlotRegistry = EquipmentSlotRegistry(engineConfig.equipment)
    private val genderRegistry = GenderRegistry(engineConfig.genders)
    private val craftingSkillRegistry = dev.ambon.engine.crafting.CraftingSkillRegistry(engineConfig.craftingSkills)

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
            guildSystem = guildSystem,
            sessionLifecycle = sessionLifecycleCoordinator,
            router = router,
            playerLocationIndex = playerLocationIndex,
            handoffManager = handoffManager,
            getEngineScope = { engineScope },
            metrics = metrics,
            classRegistry = classRegistry,
            raceRegistry = raceRegistry,
            imagesBaseUrl = imagesBaseUrl,
            debugClassesEnabled = engineConfig.debug.enableSwarmClass,
            maxWrongPasswordRetries = loginConfig.maxWrongPasswordRetries,
            maxFailedLoginAttemptsBeforeDisconnect = loginConfig.maxFailedAttemptsBeforeDisconnect,
            maxConcurrentLogins = loginConfig.maxConcurrentLogins,
            demoEnabled = engineConfig.characterCreation.demoEnabled,
            demoDefaultRace = engineConfig.characterCreation.defaultRace,
            demoDefaultClass = engineConfig.characterCreation.defaultClass,
            onAfterLogin = { sid ->
                players.get(sid)?.lastActivityEpochMs = clock.millis()
                housingSystem?.onPlayerLogin(sid)
                sendHousingGmcp(sid)
                guildSystem?.onPlayerLogin(sid)
                guildHallSystem?.onPlayerLogin(sid)
                friendsSystem.onPlayerLogin(sid)
                sendQuestListGmcp(sid)
                markStatsDirty(sid)
                broadcastServerWho()
                issueResumeToken(sid)
                // Emit panels that every session needs up-front. These were
                // previously only fired on auth-token / resume paths, so a
                // freshly created character saw empty Dungeon/Lottery panels
                // until the first relog. See issue #1045.
                emitDungeonCatalog(sid)
                players.get(sid)?.let { p -> emitLotteryInfo(sid, p.name) }
                // Seed the client's time-of-day + season immediately; otherwise it
                // wouldn't learn the season until the next quarter-year rollover.
                gmcpEmitter.sendWorldTime(sid, worldTimePayload())
                // Push daily/weekly/global quest state so the client doesn't
                // need a manual refresh button. See issue #1091.
                dailyQuestSystem?.let { dqs ->
                    dqs.checkReset(sid)
                    gmcpEmitter.sendDailyQuests(sid, dqs)
                    gmcpEmitter.sendWeeklyQuests(sid, dqs)
                }
                globalQuestSystem?.let { sys ->
                    val status = sys.getStatus()
                    if (status != null) {
                        gmcpEmitter.sendGlobalQuest(sid, status, sys.getPlayerProgress(sid) ?: 0)
                    } else {
                        gmcpEmitter.sendGlobalQuestInactive(sid)
                    }
                }
            },
            // Only password/create logins need a fresh auth token — the client
            // doesn't have one yet. Auto-relog via Session.Authenticate and
            // Session.Resume reuse the token the client already presented, so
            // we avoid rotating on those paths (the rotation was racing flaky
            // mobile connections and leaving localStorage with a stale token).
            onFreshPasswordLogin = { sid -> issueAuthToken(sid) },
        )
    }

    private val gracePeriodManager: SessionGracePeriodManager? =
        if (engineConfig.sessionResumeGracePeriodMs > 0) {
            SessionGracePeriodManager(engineConfig.sessionResumeGracePeriodMs, clock)
        } else {
            null
        }

    private val sessionEventHandler by lazy {
        SessionEventHandler(
            players = players,
            markAwaitingName = { sid -> loginFlowHandler.pendingLogins[sid] = LoginState.AwaitingName },
            clearLoginState = { sid ->
                loginFlowHandler.pendingLogins.remove(sid)
                loginFlowHandler.releasePermitIfHeld(sid)
            },
            failedLoginAttempts = loginFlowHandler.failedLoginAttempts,
            sessionAnsiDefaults = loginFlowHandler.sessionAnsiDefaults,
            gmcpSessions = gmcpSessions,
            gmcpDirtyVitals = gmcpDirtyVitals,
            gmcpDirtyStatusEffects = gmcpDirtyStatusEffects,
            gmcpDirtyGroup = gmcpDirtyGroup,
            gmcpDirtyCombat = gmcpDirtyCombat,
            gmcpEmitter = gmcpEmitter,
            handoffManager = handoffManager,
            removePendingWhoRequestsFor = interEngineEventHandler::removePendingWhoRequestsFor,
            sessionLifecycle = sessionLifecycleCoordinator,
            gracePeriodManager = gracePeriodManager,
            promptForName = loginFlowHandler::promptForName,
            showLoginScreen = { sid -> outbound.send(OutboundEvent.ShowLoginScreen(sid)) },
            onPlayerLoggedOut = { player, sid ->
                log.info { "Player logged out: name=${player.name} sessionId=$sid" }
                puzzleSystem.removeSession(sid)
                akathavaeSystem.onSessionRemoved(sid)
                for (dismissed in petSystem.onOwnerDisconnect(sid)) {
                    broadcastPetRemoved(dismissed)
                }
                tradeSystem.cancelForPlayer(sid)
                val endedDuel = duelSystem.onPlayerDisconnect(sid)
                if (endedDuel != null) {
                    val other = if (endedDuel.player1 == sid) endedDuel.player2 else endedDuel.player1
                    outbound.send(
                        OutboundEvent.SendInfo(other, "Your duel opponent disconnected. Duel ended."),
                    )
                }
                val cancelledAuctions = auctionSystem.cancelAllForPlayer(sid)
                if (cancelledAuctions.isNotEmpty()) {
                    val payload = auctionSystem.allListings().map {
                        GmcpEmitter.AuctionListingPayload(it.id, it.item.item.displayName, it.item.id.value, it.price, it.sellerName)
                    }
                    for (p in players.allPlayers()) {
                        gmcpEmitter.sendAuctionList(p.sessionId, payload)
                    }
                }
                lotterySystem.onDisconnect(sid)
                playerLocationIndex?.unregister(player.name)
                broadcastToRoom(players, outbound, player.roomId, "${player.name} leaves.", sid)
                friendsSystem.onPlayerLogout(player.name)
                broadcastServerWho()
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
            routeCommandLine = { sid, line ->
                if (players.get(sid)?.mailCompose != null) {
                    mailHandler.handleComposeLine(sid, line)
                } else if (players.get(sid)?.possessedMobId != null) {
                    adminHandler.handlePossessedCommand(sid, line)
                } else {
                    router.handle(sid, CommandParser.parse(line))
                }
            },
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
            guildSystem = guildSystem,
            trainerRegistry = trainerRegistry,
            worldState = worldState,
            gmcpEmitter = gmcpEmitter,
            onResumeRequested = if (gracePeriodManager != null) ::handleSessionResume else null,
            onAuthenticateRequested = { sid, token, name -> handleSessionAuthenticate(sid, token, name) },
            onLogoutRequested = ::handleSessionLogout,
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
            gmcpDirtyCombat = gmcpDirtyCombat,
            gmcpDirtyStats = gmcpDirtyStats,
            players = players,
            mobs = mobs,
            items = items,
            statusEffectSystem = statusEffectSystem,
            groupSystem = groupSystem,
            combatSystem = combatSystem,
            gmcpEmitter = gmcpEmitter,
            bindings = engineConfig.stats.bindings,
            metrics = metrics,
            classRegistry = classRegistry,
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

    private val timedRespawnHandler by lazy {
        TimedRespawnHandler(
            world = world,
            items = items,
            players = players,
            outbound = outbound,
            worldState = worldState,
            gmcpEmitter = gmcpEmitter,
            clock = clock,
        )
    }

    private val mobVariantRoller = MobVariantRoller(config = engineConfig.mobVariants)

    private val zoneResetHandler by lazy {
        ZoneResetHandler(
            world = world,
            mobs = mobs,
            items = items,
            players = players,
            outbound = outbound,
            worldState = worldState,
            mobRemovalCoordinator = mobRemovalCoordinator,
            mobSystem = mobSystem,
            behaviorTreeSystem = behaviorTreeSystem,
            gmcpEmitter = gmcpEmitter,
            clock = clock,
            variantRoller = mobVariantRoller,
            isMobInCombat = { mobId -> combatSystem.isMobInCombat(mobId) },
        )
    }

    private val conditionalSpawnHandler by lazy {
        ConditionalSpawnHandler(
            world = world,
            mobs = mobs,
            players = players,
            outbound = outbound,
            gmcpEmitter = gmcpEmitter,
            mobSystem = mobSystem,
            behaviorTreeSystem = behaviorTreeSystem,
            mobRemovalCoordinator = mobRemovalCoordinator,
            variantRoller = mobVariantRoller,
            period = { worldTimeSystem.period() },
            season = { seasonSystem.season() },
            weatherForZone = { zone -> weatherSystem.weatherForZone(zone) },
            activeEventFlags = { worldEventSystem.activeFlags() },
            isMobInCombat = { mobId -> combatSystem.isMobInCombat(mobId) },
            clock = clock,
        )
    }

    /**
     * GMCP packages each session has opted into (e.g. "Char.Vitals", "Room.Info").
     *
     * Thread safety: all reads and writes happen on the single-threaded engine
     * dispatcher (via [GmcpEventHandler], [SessionEventHandler], and the
     * [GmcpEmitter.supportsPackage] lambda), so a plain [MutableMap] is safe.
     * If any access ever moves off the engine thread, switch to ConcurrentHashMap.
     */
    private val gmcpSessions = mutableMapOf<SessionId, MutableSet<String>>()

    /** Sessions whose vitals changed this tick and need a Char.Vitals push. */
    private val gmcpDirtyVitals = mutableSetOf<SessionId>()

    /** Mobs whose HP changed this tick and need a Room.UpdateMob push. */
    private val gmcpDirtyMobs = mutableSetOf<MobId>()

    /** Sessions whose status effects changed this tick and need a Char.StatusEffects push. */
    private val gmcpDirtyStatusEffects = mutableSetOf<SessionId>()

    /** Sessions whose group membership changed this tick and need a Group.Info push. */
    private val gmcpDirtyGroup = mutableSetOf<SessionId>()

    /** Sessions whose combat target changed this tick and need a Char.Combat push. */
    private val gmcpDirtyCombat = mutableSetOf<SessionId>()

    /** Sessions whose stats changed this tick and need a Char.Stats push. */
    private val gmcpDirtyStats = mutableSetOf<SessionId>()

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
            getCombatTarget = { sid ->
                combatSystem.getCombatTarget(sid)?.let { mob ->
                    CombatTargetInfo(
                        id = mob.id.value,
                        name = mob.name,
                        hp = mob.hp,
                        maxHp = mob.maxHp,
                        image = mob.image,
                        category = mob.category,
                        tint = mob.tint,
                        variantName = mob.variantName,
                    )
                }
            },
            statRegistry = statRegistry,
            equipmentSlotRegistry = equipmentSlotRegistry,
            imagesBaseUrl = imagesBaseUrl,
            voicesBaseUrl = voicesBaseUrl,
            voicesEnabled = voicesEnabled,
            globalAssets = globalAssets,
            spriteRegistry = spriteRegistry,
            getMobEffects = { mobId -> statusEffectSystem.activeMobEffects(mobId) },
            commandEntries = engineConfig.commands.entries,
            emotePresets = engineConfig.emotePresets.presets,
            prestigeEnabled = { prestigeSystem.isEnabled() },
            prestigeMaxRank = { prestigeSystem.maxRank },
            prestigeAvailableXp = { player -> prestigeSystem.availableXp(player) },
            prestigeNextCost = { rank -> prestigeSystem.xpCostForNextRank(rank) },
            prestigePerkPayloads = { currentRank, maxRank ->
                (1..maxRank).map { rank ->
                    val perk = prestigeSystem.perkForRank(rank)
                    PrestigePerkPayload(
                        rank = rank,
                        type = perk?.type?.uppercase() ?: "",
                        description = perk?.description ?: "-",
                        earned = rank <= currentRank,
                    )
                }
            },
            environmentConfig = engineConfig.environment,
            featureFlags = {
                ServerFeaturesPayload(
                    dailyQuests = engineConfig.dailyQuests.enabled,
                    weeklyQuests = engineConfig.dailyQuests.enabled,
                    globalQuests = engineConfig.globalQuests.enabled,
                    autoQuests = engineConfig.autoQuests.enabled,
                )
            },
            sanctumRoomId = {
                engineConfig.death.sanctumRoom?.let { RoomId(it) }?.takeIf { world.rooms.containsKey(it) }
            },
            worldAreas = buildWorldAreaPayloads(world),
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

    fun markCombatDirty(sessionId: SessionId) {
        gmcpDirtyCombat.add(sessionId)
    }

    fun markStatsDirty(sessionId: SessionId) {
        gmcpDirtyStats.add(sessionId)
    }

    private val dirtyNotifier =
        object : DirtyNotifier {
            override fun playerVitalsDirty(sessionId: SessionId) = markVitalsDirty(sessionId)

            override fun playerStatusDirty(sessionId: SessionId) = markStatusDirty(sessionId)

            override fun mobHpDirty(mobId: MobId) = markMobHpDirty(mobId)

            override fun playerCombatDirty(sessionId: SessionId) = markCombatDirty(sessionId)

            override fun playerStatsDirty(sessionId: SessionId) = markStatsDirty(sessionId)
        }

    fun markGroupDirty(sessionId: SessionId) {
        gmcpDirtyGroup.add(sessionId)
    }

    private val mobSystem = MobSystem()

    init {
        StatusEffectRegistryLoader.load(engineConfig.statusEffects, statusEffectRegistry)
    }

    private val statusEffectSystem =
        StatusEffectSystem(
            registry = statusEffectRegistry,
            players = players,
            mobs = mobs,
            outbound = outbound,
            clock = clock,
            dirtyNotifier = dirtyNotifier,
            effectTypes = engineConfig.effectTypes,
            bindings = engineConfig.stats.bindings,
        )
    private val groupSystem: GroupSystem =
        GroupSystem(
            players = players,
            outbound = outbound,
            clock = clock,
            maxGroupSize = engineConfig.group.maxSize,
            inviteTimeoutMs = engineConfig.group.inviteTimeoutMs,
            markGroupDirty = ::markGroupDirty,
            classRegistry = classRegistry,
            gmcpEmitter = gmcpEmitter,
        )
    private val guildSystem: GuildSystem? =
        if (guildRepo != null) {
            GuildSystem(
                players = players,
                guildRepo = guildRepo!!,
                outbound = outbound,
                clock = clock,
                maxSize = engineConfig.guild.maxSize,
                inviteTimeoutMs = engineConfig.guild.inviteTimeoutMs,
                markPlayerDirty = { sid -> players.persistPlayer(sid) },
                gmcpEmitter = gmcpEmitter,
                rankConfig = engineConfig.guildRanks,
            )
        } else {
            null
        }
    private val guildHallSystem: GuildHallSystem? =
        if (guildSystem != null && guildRepo != null && engineConfig.guildHalls.enabled && engineConfig.guildHalls.templates.isNotEmpty()) {
            GuildHallSystem(
                players = players,
                guildRepo = guildRepo!!,
                world = world,
                outbound = outbound,
                config = engineConfig.guildHalls,
                rankConfig = engineConfig.guildRanks,
                markPlayerDirty = { sid -> players.persistPlayer(sid) },
                gmcpEmitter = gmcpEmitter,
                guildSystem = guildSystem!!,
            )
        } else {
            null
        }
    private val housingSystem: HousingSystem? =
        if (houseRepo != null && engineConfig.housing.enabled) {
            HousingSystem(
                players = players,
                houseRepo = houseRepo!!,
                world = world,
                outbound = outbound,
                items = items,
                config = engineConfig.housing,
                clock = clock,
                markPlayerDirty = { sid -> players.persistPlayer(sid) },
                imagesBaseUrl = imagesBaseUrl,
            )
        } else {
            null
        }
    private val friendsSystem =
        FriendsSystem(
            players = players,
            outbound = outbound,
            gmcpEmitter = gmcpEmitter,
            maxFriends = engineConfig.friends.maxFriends,
            markPlayerDirty = { sid -> players.persistPlayer(sid) },
        )
    private val petSystem = PetSystem(
        config = engineConfig.pets,
        mobs = mobs,
        clock = clock,
        imagesBaseUrl = imagesBaseUrl,
    )
    private val combatSystem =
        CombatSystem(
            players = players,
            mobs = mobs,
            items = items,
            outbound = outbound,
            clock = clock,
            progression = progression,
            metrics = metrics,
            dirtyNotifier = dirtyNotifier,
            statusEffects = statusEffectSystem,
            groupSystem = groupSystem,
            config = CombatSystemConfig(
                tickMillis = engineConfig.combat.tickMillis,
                groupXpBonusPerMember = engineConfig.group.xpBonusPerMember,
                detailedFeedbackEnabled = engineConfig.combat.feedback.enabled,
                detailedFeedbackRoomBroadcastEnabled = engineConfig.combat.feedback.roomBroadcastEnabled,
                bindings = engineConfig.stats.bindings,
            ),
            callbacks = CombatSystemCallbacks(
                onMobRemoved = ::onCombatMobRemoved,
                onLevelUp = ::onCombatLevelUp,
                onMobKilledByPlayer = ::onCombatMobKilledByPlayer,
                onRoomItemsChanged = ::syncRoomItemsForRoom,
            ),
            classRegistry = classRegistry,
            petSystem = petSystem,
        )

    private val akathavaeSystem =
        AkathavaeSystem(
            players = players,
            items = items,
            world = world,
            outbound = outbound,
            combat = combatSystem,
            worldState = worldState,
            progression = progression,
            classRegistry = classRegistry,
            statusEffects = statusEffectSystem,
            clock = clock,
            config = engineConfig.akathavae,
            metrics = metrics,
            markVitalsDirty = ::markVitalsDirty,
            onLevelUp = ::onCombatLevelUp,
            gmcpEmitter = gmcpEmitter,
        )

    private val regenSystem =
        RegenSystem(
            players = players,
            items = items,
            clock = clock,
            baseIntervalMs = engineConfig.regen.baseIntervalMillis,
            minIntervalMs = engineConfig.regen.minIntervalMillis,
            hpRegenPercent = engineConfig.regen.regenPercent,
            manaBaseIntervalMs = engineConfig.regen.mana.baseIntervalMillis,
            manaMinIntervalMs = engineConfig.regen.mana.minIntervalMillis,
            manaRegenPercent = engineConfig.regen.mana.regenPercent,
            inCombatMultiplier = engineConfig.regen.inCombatMultiplier,
            inCombat = { sid -> combatSystem.isInCombat(sid) },
            innMultiplier = engineConfig.regen.innMultiplier,
            inInn = { sid -> players.get(sid)?.roomId?.let { world.rooms[it]?.inn } == true },
            tickIntervalMs = tickMillis,
            cycleTargetMs = engineConfig.regen.cycleTargetMillis,
            minPlayersPerTick = engineConfig.regen.minPlayersPerTick,
            maxPlayersPerTick = engineConfig.regen.maxPlayersPerTick,
            bindings = engineConfig.stats.bindings,
            metrics = metrics,
            dirtyNotifier = dirtyNotifier,
            classRegistry = classRegistry,
        )

    init {
        AbilityRegistryLoader.load(engineConfig.abilities, abilityRegistry, imagesBaseUrl)
    }

    private val worldTimeSystem = WorldTimeSystem(
        config = engineConfig.worldTime,
        clock = clock,
    )

    private val seasonSystem = SeasonSystem(
        config = engineConfig.season,
        clock = clock,
    )

    private val weatherSystem = WeatherSystem(
        config = engineConfig.weather,
        clock = clock,
    )

    private val worldEventSystem = WorldEventSystem(
        config = engineConfig.worldEvents,
        clock = clock,
    )

    private val leaderboardSystem: LeaderboardSystem? =
        persistence.playerRepo?.let { repo ->
            LeaderboardSystem(
                playerRepo = repo,
                playerRegistry = players,
                config = engineConfig.leaderboard,
            )
        }

    private val prestigeSystem: PrestigeSystem = PrestigeSystem(
        config = engineConfig.prestige,
        progression = progression,
    )

    private val globalQuestSystem: GlobalQuestSystem? =
        if (engineConfig.globalQuests.enabled) {
            GlobalQuestSystem(
                config = engineConfig.globalQuests,
                clock = clock,
                playerCount = { players.allPlayers().size },
                resolvePlayerName = { sid -> players.get(sid)?.name },
            )
        } else {
            null
        }

    private var lastTimePeriod: TimePeriod = worldTimeSystem.period()
    private var lastSeason: Season = seasonSystem.season()

    private val abilitySystem: AbilitySystem =
        AbilitySystem(
            players = players,
            registry = abilityRegistry,
            outbound = outbound,
            combat = combatSystem,
            clock = clock,
            items = items,
            bindings = engineConfig.stats.bindings,
            dirtyNotifier = dirtyNotifier,
            statusEffects = statusEffectSystem,
            groupSystem = groupSystem,
            mobs = mobs,
            petSystem = petSystem,
            progression = progression,
            classRegistry = classRegistry,
            onCombatEvent = { sid, event -> gmcpEmitter.sendCombatEvent(sid, event) },
            onSummonPet = { sid, templateKey, durationMs ->
                val player = players.get(sid) ?: return@AbilitySystem
                metrics.onGameEvent("pet", "summoned")
                // Snapshot existing pets before summon so we can broadcast their removal —
                // summon() replaces the old pet internally but clients don't hear about it,
                // leaving a ghost sprite until the owner moves rooms. See issue #1093.
                val replacedPets = petSystem.getPets(sid).map { Triple(it.id, it.roomId, it.name) }
                val classDef = classRegistry.get(player.playerClass)
                val playerStats = resolvePlayerStats(player, items, statusEffectSystem, classRegistry)
                val equipBonuses = items.equipmentBonuses(sid, classDef)
                val dmgRange = combatSystem.damageRangeForDisplay(player, playerStats, equipBonuses.attack)
                val ownerStats = PetSystem.OwnerStats(
                    maxHp = player.maxHp,
                    damageMin = dmgRange.first,
                    damageMax = dmgRange.last,
                    armor = equipBonuses.armor,
                )
                val pet = petSystem.summon(sid, templateKey, player.roomId, ownerStats, durationMs, player.name)
                if (pet != null) {
                    outbound.send(OutboundEvent.SendText(sid, "You summon ${pet.name}!"))
                    emitPetState(sid, pet)
                    for ((oldId, oldRoomId, _) in replacedPets) {
                        if (oldId == pet.id) continue
                        gmcpEmitter.broadcastRoomRemoveMob(oldRoomId, oldId.value, players)
                        val oldRoomMobs = mobs.mobsInRoom(oldRoomId)
                        val oldMobInfo = gmcpEmitter.buildMobInfoEntries(oldRoomMobs)
                        gmcpEmitter.broadcastRoomMobInfo(oldRoomId, oldMobInfo, players)
                    }
                    // Refresh the room mob list for the summoner and anyone else in the room so
                    // the pet becomes visible immediately (without waiting for a movement tick).
                    // See issue #1066.
                    gmcpEmitter.broadcastRoomAddMob(player.roomId, pet, players)
                    val mobsInRoom = mobs.mobsInRoom(player.roomId)
                    val mobInfoEntries = gmcpEmitter.buildMobInfoEntries(mobsInRoom)
                    gmcpEmitter.broadcastRoomMobInfo(player.roomId, mobInfoEntries, players)
                } else {
                    outbound.send(OutboundEvent.SendText(sid, "Failed to summon pet."))
                }
            },
        ).also {
            it.onCooldownStarted = { sid, abilityId, cooldownMs ->
                gmcpEmitter.sendCharCooldown(sid, abilityId, cooldownMs)
            }
        }

    private val gatheringRegistry = GatheringRegistry()
    private val craftingRegistry = CraftingRegistry()
    private val craftingSystem = CraftingSystem(
        gatheringRegistry = gatheringRegistry,
        craftingRegistry = craftingRegistry,
        config = engineConfig.crafting,
        clock = clock,
    )

    private val enchantSystem = EnchantSystem(
        config = engineConfig.enchanting,
        items = items,
        craftingSystem = craftingSystem,
    )

    private val dungeonRegistry = DungeonRegistry().also { reg ->
        world.dungeonTemplates.forEach { reg.register(it) }
    }

    private val dungeonManager = DungeonManager(
        world = world,
        mobs = mobs,
        dungeonRegistry = dungeonRegistry,
    )

    private val tradeSystem = TradeSystem(items = items)

    private val duelSystem = DuelSystem(clock = clock)

    private val reputationSystem = ReputationSystem(config = engineConfig.factions)

    private val puzzleSystem = PuzzleSystem(world = world, clock = clock)

    private val currencySystem = CurrencySystem(config = engineConfig.currencies)

    private val duelRng = java.util.Random()

    private val fleeRng = java.util.Random()

    private val auctionSystem = AuctionSystem(
        items = items,
        clock = clock,
        persistPath = java.nio.file.Path.of("data", "auction_listings.json"),
    ).also { it.loadPersistedListings() }

    private val lotterySystem = LotterySystem(
        lotteryConfig = engineConfig.lottery,
        gamblingConfig = engineConfig.gambling,
        clock = clock,
        persistPath = java.nio.file.Path.of("data", "lottery_state.json"),
    ).also { it.loadPersistedState() }

    private val dialogueSystem =
        DialogueSystem(
            mobs = mobs,
            players = players,
            outbound = outbound,
            gmcpEmitter = gmcpEmitter,
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
            reputationSystem = reputationSystem,
            progression = progression,
            world = world,
        )

    private val dailyQuestSystem: DailyQuestSystem? =
        if (engineConfig.dailyQuests.enabled) {
            DailyQuestSystem(
                config = engineConfig.dailyQuests,
                players = players,
                clock = clock,
                progression = progression,
            )
        } else {
            null
        }

    private val autoQuestSystem: AutoQuestSystem? =
        if (engineConfig.autoQuests.enabled) {
            AutoQuestSystem(
                config = engineConfig.autoQuests,
                world = world,
                players = players,
                clock = clock,
            )
        } else {
            null
        }

    private val achievementCategoryRegistry = AchievementCategoryRegistry(engineConfig.achievementCategories)

    private val achievementSystem =
        AchievementSystem(
            registry =
                achievementRegistry.also { reg ->
                    AchievementLoader.loadFromResource("world/achievements.yaml", reg, achievementCategoryRegistry)
                },
            players = players,
            outbound = outbound,
            gmcpEmitter = gmcpEmitter,
            categoryRegistry = achievementCategoryRegistry,
            spriteRegistry = spriteRegistry,
            progression = progression,
        )

    init {
        // Late-wire combat event / gain callbacks (avoids circular type inference with gmcpEmitter)
        combatSystem.onCombatEvent = { sid, event -> gmcpEmitter.sendCombatEvent(sid, event) }
        combatSystem.onPlayerEnteredCombat = { sid -> dialogueSystem.endConversation(sid) }
        combatSystem.onPetSkillCast = { sid -> emitPetSkills(sid, petSystem.getActivePet(sid)) }
        combatSystem.onXpGained = { sid, amount, source -> gmcpEmitter.sendCharGain(sid, "xp", amount, source) }
        combatSystem.onGoldGained = { sid, amount, source -> gmcpEmitter.sendCharGain(sid, "gold", amount, source) }
        combatSystem.onPlayerDeath = { sid -> cleanupOnPlayerDeath(sid) }
        combatSystem.zoneStartRoomLookup = { zoneId -> world.zoneStartRoom(zoneId) }
        combatSystem.deathConfig = engineConfig.death
        combatSystem.sanctumRoomLookup = {
            engineConfig.death.sanctumRoom?.let { RoomId(it) }?.takeIf { world.rooms.containsKey(it) }
        }
        // Single assignment: a second `onPvpKill =` would silently overwrite the first.
        combatSystem.onPvpKill = { killerSid ->
            metrics.onGameEvent("duel", "completed")
            notifyDailyQuest(killerSid, "pvpKill")
            val killer = players.get(killerSid)
            if (killer != null &&
                currencySystem.award(killer, "honor", currencySystem.honorPerPvpKill)
            ) {
                val displayName = currencySystem.getDefinition("honor")?.displayName ?: "Honor"
                outbound.send(
                    OutboundEvent.SendInfo(
                        killerSid,
                        "[Currency] You receive ${currencySystem.honorPerPvpKill} $displayName.",
                    ),
                )
                emitCurrencies(killerSid, killer)
            }
        }
        statusEffectSystem.onCombatEvent = { sid, event -> gmcpEmitter.sendCombatEvent(sid, event) }

        questSystem.onQuestAccepted = { _, _ -> metrics.onGameEvent("quest", "accepted") }
        questSystem.onQuestAbandoned = { _, _ -> metrics.onGameEvent("quest", "abandoned") }
        questSystem.onQuestCompleted = { sid, questId ->
            metrics.onGameEvent("quest", "completed")
            achievementSystem.onQuestCompleted(sid, questId)
            val player = players.get(sid)
            if (player != null) {
                val changes = reputationSystem.onQuestCompleted(player, questId)
                for (change in changes) {
                    val factionName = reputationSystem.getFaction(change.factionId)?.name ?: change.factionId
                    val sign = if (change.amount > 0) "+" else ""
                    outbound.send(
                        OutboundEvent.SendInfo(
                            sid,
                            "[Reputation] $factionName: $sign${change.amount} " +
                                "(${reputationSystem.tierLabel(change.newStanding)})",
                        ),
                    )
                }
                if (changes.isNotEmpty()) emitFactions(sid, player)

                // Award secondary currencies from quest rewards
                val questDef = questRegistry.get(questId)
                if (questDef != null) {
                    var awardedAny = false
                    for ((currencyId, amount) in questDef.rewards.currencies) {
                        if (!currencySystem.award(player, currencyId, amount)) continue
                        awardedAny = true
                        val displayName = currencySystem.getDefinition(currencyId)?.displayName ?: currencyId
                        outbound.send(
                            OutboundEvent.SendInfo(sid, "[Currency] You receive $amount $displayName."),
                        )
                    }
                    if (awardedAny) emitCurrencies(sid, player)
                }
            }
        }
        questSystem.onQuestListChanged = { sid ->
            sendQuestListGmcp(sid)
            refreshRoomMobInfoForPlayer(sid)
        }
        questSystem.onQuestObjectiveUpdated = { sid, questId, objIndex, current, required, readyToTurnIn ->
            gmcpEmitter.sendQuestUpdate(sid, questId, objIndex, current, required, readyToTurnIn)
            // When the final objective ticks over to complete, the resolved
            // turn-in NPC's `questComplete` flag flips — refresh so the popout
            // and canvas indicator pick it up without a room exit/enter.
            if (readyToTurnIn) refreshRoomMobInfoForPlayer(sid)
        }
        questSystem.onQuestCompletedGmcp = { sid, summary ->
            gmcpEmitter.sendQuestComplete(
                sid,
                summary.questId,
                summary.questName,
                summary.questDescription,
                summary.rewardXp,
                summary.rewardGold,
                summary.rewardCurrencies,
                summary.rewardItems,
            )
            sendQuestListGmcp(sid)
            refreshRoomMobInfoForPlayer(sid)
        }
        questSystem.onItemRewarded = { sid, item ->
            gmcpEmitter.sendCharItemsAdd(sid, item)
        }
        questSystem.onItemsConsumed = { sid ->
            gmcpEmitter.sendCharItemsList(sid, items.inventory(sid), items.equipment(sid))
        }
        questSystem.onLevelUp = ::onCombatLevelUp
        achievementSystem.onLevelUp = ::onCombatLevelUp
        combatSystem.onItemAutoLooted = { sid, item ->
            gmcpEmitter.sendCharItemsAdd(sid, item)
            questSystem.onItemCollected(sid, item)
        }
        guildSystem?.onGuildCreated = { sid ->
            metrics.onGameEvent("guild", "created")
            achievementSystem.onGuildCreated(sid)
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
            isMobRooted = { mobId -> statusEffectSystem.hasMobEffect(mobId, "root") },
            isMobPossessed = { mobId -> players.allPlayers().any { it.possessedMobId == mobId } },
            startMobCombat = { mobId, sessionId -> combatSystem.startMobCombat(mobId, sessionId) },
            fleeMob = { mobId -> combatSystem.fleeMob(mobId) },
            gmcpEmitter = gmcpEmitter,
            minActionDelayMs = engineConfig.mob.minActionDelayMillis,
            maxActionDelayMs = engineConfig.mob.maxActionDelayMillis,
            metrics = metrics,
        )

    private val sessionLifecycleCoordinator = SessionLifecycleCoordinator(
        listOfNotNull(
            combatSystem,
            regenSystem,
            abilitySystem,
            statusEffectSystem,
            dialogueSystem,
            groupSystem,
            guildSystem,
            guildHallSystem,
            housingSystem,
            autoQuestSystem,
        ),
    )

    private val mobRemovalCoordinator = MobRemovalCoordinator(
        combatSystem = combatSystem,
        dialogueSystem = dialogueSystem,
        behaviorTreeSystem = behaviorTreeSystem,
        mobs = mobs,
        mobSystem = mobSystem,
        statusEffectSystem = statusEffectSystem,
    )

    private val hotReloadManager: HotReloadManager? =
        if (worldLoader != null) {
            HotReloadManager(
                world = world,
                mobs = mobs,
                items = items,
                players = players,
                outbound = outbound,
                shopRegistry = shopRegistry,
                trainerRegistry = trainerRegistry,
                gatheringRegistry = gatheringRegistry,
                craftingRegistry = craftingRegistry,
                questRegistry = questRegistry,
                abilityRegistry = abilityRegistry,
                statusEffectRegistry = statusEffectRegistry,
                equipmentSlotRegistry = equipmentSlotRegistry,
                achievementRegistry = achievementRegistry,
                achievementCategoryRegistry = achievementCategoryRegistry,
                mobSystem = mobSystem,
                behaviorTreeSystem = behaviorTreeSystem,
                gmcpEmitter = gmcpEmitter,
                worldState = worldState,
                engineConfig = engineConfig,
                imagesBaseUrl = imagesBaseUrl,
                worldLoader = worldLoader,
                onZoneScheduleRefresh = {
                    zoneResetHandler.refreshSchedule()
                    timedRespawnHandler.refresh()
                    conditionalSpawnHandler.refresh()
                },
            )
        } else {
            null
        }

    private val router = CommandRouter(outbound = outbound, players = players)
    private val communicationHandler: CommunicationHandler
    private val adminHandler: AdminHandler
    private val mailHandler: MailHandler
    private val autoQuestHandler: AutoQuestHandler

    init {
        val crossZoneMove: (suspend (SessionId, RoomId) -> Unit)? = if (handoffManager != null) ::handleCrossZoneMove else null
        val phaseCallback: (suspend (SessionId, String?) -> PhaseResult)? =
            if (zoneRegistry != null && zoneRegistry!!.instancingEnabled() && handoffManager != null) {
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
            gatheringRegistry = gatheringRegistry,
            shopRegistry = shopRegistry,
            economyConfig = engineConfig.economy,
            questSystem = questSystem,
            classRegistry = classRegistry,
            raceRegistry = raceRegistry,
            statRegistry = statRegistry,
            equipmentSlotRegistry = equipmentSlotRegistry,
            genderRegistry = genderRegistry,
            leaderboardSystem = leaderboardSystem,
            trainerRegistry = trainerRegistry,
            puzzleSystem = puzzleSystem,
            bankConfig = engineConfig.bank,
            stylistConfig = engineConfig.stylist,
            akathavaeSystem = akathavaeSystem,
            metrics = metrics,
        )

        // Push a fresh room look when a dead player respawns, so the web client
        // stops showing the room they died in.
        combatSystem.onPlayerRespawned = { sid -> ctx.sendLook(sid) }

        // Flee relocates the player to an adjacent room so aggressive mobs (or other
        // players in PvP) don't just keep hitting them. Prefer the direction opposite
        // to the one they walked in from — they came from that room and survived, so
        // it's more likely to be safe than a random new room.
        combatSystem.onPlayerFled = onPlayerFled@{ sid, _ ->
            val player = players.get(sid) ?: return@onPlayerFled
            val from = player.roomId
            val room = world.rooms[from] ?: return@onPlayerFled

            val candidates = room.exits.entries.filter { (dir, dest) ->
                if (!world.rooms.containsKey(dest)) return@filter false
                if (room.remoteExits.contains(dir)) return@filter false
                val door = worldState.doorOnExit(from, dir)
                if (door != null && worldState.getDoorState(door.id) != LockableState.OPEN) return@filter false
                // Honor achievement gates so flee can't bypass progression locks.
                val gate = room.achievementGates[dir]
                if (gate != null && gate.achievementId !in player.unlockedAchievementIds) return@filter false
                true
            }
            if (candidates.isEmpty()) return@onPlayerFled

            val preferred = player.lastEnterDirection?.opposite()
            val chosen = candidates.firstOrNull { it.key == preferred }
                ?: candidates[fleeRng.nextInt(candidates.size)]

            movePlayerWithNotify(
                sid,
                from,
                chosen.value,
                "leaves.",
                "enters.",
                players,
                outbound,
                gmcpEmitter,
                dialogueSystem,
                direction = chosen.key,
                departVerb = "flees",
                world = world,
            )
            petSystem.followOwner(sid, chosen.value)
            ctx.sendLook(sid)
        }

        communicationHandler = CommunicationHandler(
            ctx = ctx,
            groupSystem = groupSystem,
            interEngineBus = interEngineBus,
            playerLocationIndex = playerLocationIndex,
            engineId = engineId,
            onRemoteWho = if (interEngineBus != null) interEngineEventHandler::handleRemoteWho else null,
            clock = clock,
        )

        adminHandler = AdminHandler(
            ctx = ctx,
            onShutdown = onShutdown,
            mobRemovalCoordinator = mobRemovalCoordinator,
            onCrossZoneMove = crossZoneMove,
            statusEffects = statusEffectSystem,
            interEngineBus = interEngineBus,
            engineId = engineId,
            metrics = metrics,
            onReload = hotReloadManager?.let { mgr ->
                { target -> handleReloadCommand(mgr, target) }
            },
        )

        val puzzleHandlerInstance = PuzzleHandler(
            ctx = ctx,
            puzzleSystem = puzzleSystem,
            progression = progression,
            onLevelUp = ::onCombatLevelUp,
        )

        listOf(
            NavigationHandler(
                ctx = ctx,
                statusEffects = statusEffectSystem,
                dialogueSystem = dialogueSystem,
                onCrossZoneMove = crossZoneMove,
                recallConfig = engineConfig.navigation.recall,
                deathConfig = engineConfig.death,
                housingSystem = housingSystem,
                guildHallSystem = guildHallSystem,
                onPlayerMoved = { sid, roomId ->
                    petSystem.followOwner(sid, roomId)
                    akathavaeSystem.onRoomVisited(sid)
                },
                puzzleSystem = puzzleSystem,
            ),
            communicationHandler,
            CombatHandler(
                ctx = ctx,
                abilitySystem = abilitySystem,
                statusEffects = statusEffectSystem,
                dialogueSystem = dialogueSystem,
                housingSystem = housingSystem,
                duelSystem = duelSystem,
            ),
            ProgressionHandler(
                ctx = ctx,
                progression = progression,
                abilitySystem = abilitySystem,
                statusEffects = statusEffectSystem,
                groupSystem = groupSystem,
                currencySystem = currencySystem,
            ),
            ItemHandler(
                ctx = ctx,
                questSystem = questSystem,
                abilitySystem = abilitySystem,
                dialogueSystem = dialogueSystem,
                tradeSystem = tradeSystem,
                markVitalsDirty = ::markVitalsDirty,
                markStatsDirty = ::markStatsDirty,
                metrics = metrics,
                progression = progression,
                housingSystem = housingSystem,
                skillPointsConfig = engineConfig.skillPoints,
            ),
            ShopHandler(
                ctx = ctx,
                shopRegistry = shopRegistry,
                markVitalsDirty = ::markVitalsDirty,
                economyConfig = engineConfig.economy,
                reputationSystem = reputationSystem,
            ),
            CraftingHandler(
                ctx = ctx,
                craftingSystem = craftingSystem,
                craftingSkillRegistry = craftingSkillRegistry,
                gatheringRegistry = gatheringRegistry,
                markVitalsDirty = ::markVitalsDirty,
                onItemCrafted = { sid ->
                    metrics.onGameEvent("crafting", "craft")
                    achievementSystem.onItemCrafted(sid)
                    notifyDailyQuest(sid, "craft")
                    globalQuestSystem?.onEvent(sid, GlobalQuestObjectiveType.CRAFT)
                    val crafter = players.get(sid)
                    if (crafter != null &&
                        currencySystem.award(crafter, "crafting_tokens", currencySystem.tokensPerCraft)
                    ) {
                        val displayName =
                            currencySystem.getDefinition("crafting_tokens")?.displayName ?: "Crafting Tokens"
                        outbound.send(
                            OutboundEvent.SendInfo(
                                sid,
                                "[Currency] You receive ${currencySystem.tokensPerCraft} $displayName.",
                            ),
                        )
                        emitCurrencies(sid, crafter)
                    }
                },
                onItemGathered = { sid, skill ->
                    metrics.onGameEvent("crafting", "gather")
                    achievementSystem.onItemGathered(sid, skill)
                    notifyDailyQuest(sid, "gather")
                    globalQuestSystem?.onEvent(sid, GlobalQuestObjectiveType.GATHER)
                },
            ),
            EnchantHandler(
                ctx = ctx,
                enchantSystem = enchantSystem,
            ),
            BankHandler(
                ctx = ctx,
                bankConfig = engineConfig.bank,
                markVitalsDirty = ::markVitalsDirty,
            ),
            StylistHandler(
                ctx = ctx,
                stylistConfig = engineConfig.stylist,
                progression = progression,
                abilitySystem = abilitySystem,
                markVitalsDirty = ::markVitalsDirty,
                markStatsDirty = ::markStatsDirty,
                spriteRegistry = spriteRegistry,
            ),
            AkathavaeHandler(
                ctx = ctx,
                config = engineConfig.akathavae,
                clock = clock,
                markVitalsDirty = ::markVitalsDirty,
                markStatsDirty = ::markStatsDirty,
                akathavaeSystem = akathavaeSystem,
                progression = progression,
                abilitySystem = abilitySystem,
            ),
            WorldInfoHandler(
                ctx = ctx,
                worldTimeSystem = worldTimeSystem,
                weatherSystem = weatherSystem,
                worldEventSystem = worldEventSystem,
            ),
            DialogueQuestHandler(
                ctx = ctx,
                dialogueSystem = dialogueSystem,
                questSystem = questSystem,
                questRegistry = questRegistry,
                achievementSystem = achievementSystem,
                achievementRegistry = achievementRegistry,
                housingSystem = housingSystem,
            ),
            GroupHandler(
                ctx = ctx,
                groupSystem = groupSystem,
            ),
            GuildHandler(
                ctx = ctx,
                guildSystem = guildSystem,
                guildHallSystem = guildHallSystem,
            ),
            FriendsHandler(
                ctx = ctx,
                friendsSystem = friendsSystem,
            ),
            ClaimHandler(
                ctx = ctx,
                getEngineScope = { engineScope },
                onClaimed = { sid ->
                    metrics.onGameEvent("demo", "claimed")
                    issueAuthToken(sid)
                },
            ),
            HousingHandler(
                ctx = ctx,
                housingSystem = housingSystem,
            ),
            puzzleHandlerInstance,
            WorldFeaturesHandler(ctx = ctx, puzzleHandler = puzzleHandlerInstance),
            adminHandler,
            DungeonHandler(
                ctx = ctx,
                dungeonManager = dungeonManager,
                dungeonRegistry = dungeonRegistry,
                groupSystem = groupSystem,
            ),
            TradeHandler(
                ctx = ctx,
                tradeSystem = tradeSystem,
                markVitalsDirty = ::markVitalsDirty,
            ),
            DuelHandler(
                ctx = ctx,
                duelSystem = duelSystem,
                combatSystem = combatSystem,
                markVitalsDirty = ::markVitalsDirty,
            ),
            AuctionHandler(
                ctx = ctx,
                auctionSystem = auctionSystem,
                markVitalsDirty = ::markVitalsDirty,
                playerRepo = persistence.playerRepo,
            ),
            LotteryHandler(
                ctx = ctx,
                lotterySystem = lotterySystem,
                markVitalsDirty = ::markVitalsDirty,
                getEngineScope = { engineScope },
            ),
            ReputationHandler(
                ctx = ctx,
                reputationSystem = reputationSystem,
            ),
            CurrencyHandler(
                ctx = ctx,
                currencySystem = currencySystem,
            ),
            PetHandler(
                ctx = ctx,
                petSystem = petSystem,
            ),
            SpriteHandler(
                ctx = ctx,
                spriteRegistry = spriteRegistry,
            ),
            TrainerHandler(
                ctx = ctx,
                abilitySystem = abilitySystem,
                trainerRegistry = trainerRegistry,
                skillPointsConfig = engineConfig.skillPoints,
                multiclassConfig = engineConfig.multiclass,
                respecConfig = engineConfig.respec,
                clock = clock,
                markVitalsDirty = ::markVitalsDirty,
                prestigeSkillPointBonus = { rank -> prestigeSystem.accumulatedSkillPointBonus(rank) },
            ),
            LeaderboardHandler(ctx = ctx),
            DailyQuestHandler(
                ctx = ctx,
                dailyQuestSystem = dailyQuestSystem,
            ),
            GlobalQuestHandler(
                ctx = ctx,
                globalQuestSystem = globalQuestSystem,
                clock = clock,
            ),
            PrestigeHandler(
                ctx = ctx,
                prestigeSystem = prestigeSystem,
                progression = progression,
            ),
            UiHandler(
                ctx = ctx,
                onPhase = phaseCallback,
                commandsConfig = engineConfig.commands,
            ),
        ).forEach { it.register(router) }

        mailHandler = MailHandler(ctx = ctx, clock = clock, markVitalsDirty = ::markVitalsDirty)
        mailHandler.register(router)

        autoQuestHandler = AutoQuestHandler(
            ctx = ctx,
            autoQuestSystem = autoQuestSystem,
            onQuestReward = { sid, xp, gold ->
                val ps = players.get(sid)
                if (ps != null) {
                    if (gold > 0) {
                        ps.gold += gold
                        outbound.send(OutboundEvent.SendText(sid, "You receive $gold gold."))
                        gmcpEmitter.sendCharGain(sid, "gold", gold, "bounty")
                    }
                    if (xp > 0) {
                        val levelUp = players.grantXp(sid, xp)
                        outbound.send(OutboundEvent.SendText(sid, "You gain $xp XP."))
                        gmcpEmitter.sendCharGain(sid, "xp", xp, "bounty")
                        if (levelUp != null && levelUp.levelsGained > 0) {
                            outbound.send(
                                OutboundEvent.SendInfo(sid, "Congratulations! You reached level ${levelUp.newLevel}!"),
                            )
                            onCombatLevelUp(sid, levelUp)
                        }
                    }
                    markVitalsDirty(sid)
                }
            },
        )
        autoQuestHandler.register(router)
    }

    /**
     * Coroutine scope provided by [run]; used to launch background auth coroutines
     * without blocking the engine tick loop.
     */
    private lateinit var engineScope: CoroutineScope

    init {
        // Condition-gated spawns (night-only, storm-only, …) are owned entirely
        // by ConditionalSpawnHandler; they are not present at cold start.
        //
        // Ordinary spawns roll for a rare variant here too, so the freshly-booted
        // world is already seeded with a few sightings to discover — no fanfare,
        // since nobody is connected yet; explorers simply stumble onto them.
        world.mobSpawns.filterNot { it.isConditional(world) }.forEach { spawn ->
            val variant = world.mobTemplate(spawn.templateId)?.let { mobVariantRoller.roll(it) }
            mobs.upsert(spawnToMobState(spawn, world, variant = variant))
        }
        items.loadSpawns(world.itemSpawns)
        shopRegistry.register(world.shopDefinitions)
        trainerRegistry.register(world.trainerDefinitions)
        gatheringRegistry.register(world.gatheringNodes)
        craftingRegistry.register(world.recipes)
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

            // Wire grace period cancellation into the player registry so that
            // normal logins cancel any suspended session for the same player name.
            if (gracePeriodManager != null) {
                players.cancelGracePeriod = { name ->
                    val cancelled = gracePeriodManager.cancelByName(name)
                    if (cancelled != null) {
                        log.info { "Cancelled grace period for $name (normal login)" }
                        sessionEventHandler.fullDisconnect(cancelled.sessionId, cancelled.playerState)
                    }
                }
            }

            // Load guild data into memory.
            guildSystem?.initialize()

            // Materialise guild halls.
            if (guildSystem != null && guildHallSystem != null) {
                guildHallSystem.materializeAllHalls(guildSystem.allGuilds())
            }

            // Schedule initial leaderboard population and recurring refresh.
            leaderboardSystem?.let { sys ->
                scheduleLeaderboardRefresh(sys)
            }

            // Restore persisted world state, overriding in-memory defaults.
            worldStateRepository?.load()?.let { snapshot ->
                worldState.applySnapshot(snapshot) { itemId -> items.createFromTemplate(itemId) }
            }

            var tickDebtMs = 0L
            var lastThreatCleanupMs = clock.millis()
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
                            val msg = interEngineBus!!.incoming().tryReceive().getOrNull() ?: break
                            handleInterEngineMessage(msg)
                            interEngineProcessed++
                        }
                    }

                    if (handoffManager != null) {
                        for (timedOut in handoffManager!!.expireTimedOut()) {
                            handleHandoffTimeout(timedOut)
                        }
                    }
                    interEngineEventHandler.flushDueWhoResponses()

                    // Drain any pending hot reload requests (from admin API).
                    if (reloadChannel != null && hotReloadManager != null) {
                        while (true) {
                            val req = reloadChannel.tryReceive().getOrNull() ?: break
                            val summary = handleReloadCommand(hotReloadManager, req.target)
                            req.result.complete(summary)
                        }
                    }
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
                            mobRemovalCoordinator.removeMobExternally(mobId)
                            broadcastToRoom(players, outbound, mob.roomId, "${mob.name} dies.")
                        }
                    }

                    // Regenerate player HP (time-gated internally)
                    val regenSample = Timer.start()
                    regenSystem.tick()
                    regenSample.stop(metrics.regenTickTimer)

                    // Periodic threat table cleanup — sweep stale mob entries every 60s
                    if (tickStart - lastThreatCleanupMs >= THREAT_CLEANUP_INTERVAL_MS) {
                        lastThreatCleanupMs = tickStart
                        combatSystem.cleanupStaleThreatEntries()
                    }

                    // Tick duel combat
                    tickDuels()

                    // Tick PvP zone combat
                    combatSystem.tickPvpCombat()

                    // Expire timed pets
                    for (expired in petSystem.tick()) {
                        outbound.send(
                            OutboundEvent.SendInfo(
                                expired.ownerSessionId,
                                "${expired.petName} fades away.",
                            ),
                        )
                        emitPetState(expired.ownerSessionId, null)
                        broadcastPetRemoved(
                            PetSystem.DismissedPet(expired.petId, expired.roomId, expired.petName),
                        )
                    }

                    // Tick world time + season — broadcast on either change
                    val newPeriod = worldTimeSystem.tick(lastTimePeriod)
                    val newSeason = seasonSystem.tick(lastSeason)
                    if (newPeriod != null) lastTimePeriod = newPeriod
                    if (newSeason != null) {
                        lastSeason = newSeason
                        for (p in players.allPlayers()) {
                            outbound.send(OutboundEvent.SendInfo(p.sessionId, "[Season] ${newSeason.description}"))
                        }
                    }
                    if (newPeriod != null || newSeason != null) {
                        gmcpEmitter.broadcastWorldTime(worldTimePayload(), players)
                    }

                    // Snapshot all players once for the remainder of this tick to avoid
                    // repeated list copies in weather / event / broadcast phases.
                    val allPlayersSnapshot = players.allPlayers()

                    // Tick weather — broadcast zone changes
                    val activeZones = allPlayersSnapshot.map { it.roomId.zone }.toSet()
                    val weatherChanges = weatherSystem.tick(activeZones)
                    for ((zone, weatherId) in weatherChanges) {
                        val def = weatherSystem.typeDefinition(weatherId)
                        for (p in players.playersInZone(zone)) {
                            gmcpEmitter.sendWorldWeather(
                                p.sessionId,
                                GmcpEmitter.WorldWeatherPayload(
                                    zone = zone,
                                    weather = weatherId,
                                    description = def?.description ?: "",
                                    particleHint = def?.particleHint ?: "",
                                    icon = def?.icon ?: "",
                                ),
                            )
                        }
                    }

                    // Tick world events — broadcast activations/deactivations
                    val eventResult = worldEventSystem.tick()
                    if (eventResult.hasChanges()) {
                        for (id in eventResult.activated) {
                            val def = engineConfig.worldEvents.definitions[id] ?: continue
                            if (def.startMessage.isNotEmpty()) {
                                for (p in allPlayersSnapshot) {
                                    outbound.send(OutboundEvent.SendInfo(p.sessionId, "[Event] ${def.startMessage}"))
                                }
                            }
                        }
                        for (id in eventResult.deactivated) {
                            val def = engineConfig.worldEvents.definitions[id] ?: continue
                            if (def.endMessage.isNotEmpty()) {
                                for (p in allPlayersSnapshot) {
                                    outbound.send(OutboundEvent.SendInfo(p.sessionId, "[Event] ${def.endMessage}"))
                                }
                            }
                        }
                        val activePayloads = worldEventSystem.activeEvents().map { (id, def) ->
                            GmcpEmitter.WorldEventPayload(id, def.displayName, def.description)
                        }
                        gmcpEmitter.broadcastWorldEvents(activePayloads, players)
                    }

                    // Tick gathering node respawns
                    craftingSystem.tickNodeRespawns()

                    // Expire auction listings
                    for (expired in auctionSystem.expireListings()) {
                        outbound.send(
                            OutboundEvent.SendInfo(
                                expired.sellerSid,
                                "[Auction] Your listing for ${expired.item.item.displayName} has expired. Item returned.",
                            ),
                        )
                        gmcpEmitter.sendCharItemsList(
                            expired.sellerSid,
                            items.inventory(expired.sellerSid),
                            items.equipment(expired.sellerSid),
                        )
                    }

                    // Tick global competitive quests
                    tickGlobalQuest(tickStart)

                    // Tick lottery drawing
                    val drawingResult = lotterySystem.tick(tickStart)
                    if (drawingResult != null) {
                        val winner = drawingResult.winnerName
                        if (winner != null) {
                            val msg = "[Lottery] $winner wins the lottery jackpot of ${drawingResult.jackpotAmount} gold!"
                            for (p in players.allPlayers()) {
                                outbound.send(OutboundEvent.SendInfo(p.sessionId, msg))
                            }
                            // Credit gold to winner if online
                            val winnerState = players.getByName(winner)
                            if (winnerState != null) {
                                winnerState.gold += drawingResult.jackpotAmount
                                markVitalsDirty(winnerState.sessionId)
                            } else {
                                // Winner is offline — credit gold via persistence
                                val repo = persistence.playerRepo
                                if (repo != null) {
                                    try {
                                        val record = repo.findByName(winner)
                                        if (record != null) {
                                            repo.save(
                                                record.copy(gold = record.gold + drawingResult.jackpotAmount),
                                            )
                                        }
                                    } catch (e: Exception) {
                                        log.warn(e) { "Failed to credit lottery winnings to offline player $winner" }
                                    }
                                }
                            }
                        } else {
                            val msg = "[Lottery] No tickets sold this round. Jackpot rolls over (${drawingResult.jackpotAmount} gold)."
                            for (p in players.allPlayers()) {
                                outbound.send(OutboundEvent.SendInfo(p.sessionId, msg))
                            }
                        }
                    }

                    // Expire grace period sessions
                    gracePeriodManager?.expireSessions()?.forEach { expired ->
                        log.info { "Running deferred disconnect for ${expired.playerState.name}" }
                        sessionEventHandler.fullDisconnect(expired.sessionId, expired.playerState)
                    }

                    metrics.recordTickPhase("simulation", simulationPhaseSample)

                    // Phase 3: Flush GMCP vitals for sessions that had changes this tick.
                    val gmcpFlushPhaseSample = Timer.start()
                    gmcpFlushHandler.flushAll()
                    metrics.recordTickPhase("gmcp_flush", gmcpFlushPhaseSample)

                    // Phase 4: Outbound flush — run scheduled actions and reset expired zones.
                    val outboundFlushPhaseSample = Timer.start()
                    val schedulerSample = Timer.start()
                    val (actionsRan, actionsDropped) = scheduler.runDue(maxActions = engineConfig.scheduler.maxActionsPerTick)
                    schedulerSample.stop(metrics.schedulerRunDueTimer)
                    metrics.onSchedulerActionsExecuted(actionsRan)
                    metrics.onSchedulerActionsDropped(actionsDropped)

                    // Expire timed auto-quests
                    if (autoQuestSystem != null) {
                        val expired = autoQuestSystem.tick(clock.millis())
                        for (sid in expired) {
                            autoQuestHandler.handleExpired(sid)
                        }
                    }

                    // Reset zones when their lifespan elapses.
                    zoneResetHandler.tick()

                    // Spawn/fade condition-gated mobs (night/storm/season/random).
                    conditionalSpawnHandler.tick()

                    // Restore item spawns / features that declare their own respawnSeconds.
                    timedRespawnHandler.tick()
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

    private suspend fun handleHandoffTimeout(timedOut: TimedOutHandoff) {
        metrics.onHandoffTimeout()
        val player = players.get(timedOut.sessionId)
        if (player == null) {
            log.warn {
                "Handoff timeout for session=${timedOut.sessionId.value} but player already " +
                    "disconnected; state cleaned up."
            }
            return
        }
        log.warn {
            "Handoff timeout for session=${timedOut.sessionId.value} player=${timedOut.playerName} " +
                "targetZone=${timedOut.targetRoomId.zone}; restoring player to ${timedOut.fromRoomId.value}"
        }
        // Restore the player's room to the original source room in case it was changed.
        player.roomId = timedOut.fromRoomId
        outbound.send(
            OutboundEvent.SendError(
                timedOut.sessionId,
                "Cross-zone move timed out. You remain where you are.",
            ),
        )
        outbound.send(OutboundEvent.SendPrompt(timedOut.sessionId))
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
        players.get(sessionId)?.lastActivityEpochMs = clock.millis()
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

    suspend fun broadcastServerWho() {
        val now = clock.millis()
        val allPlayers = players.allPlayers().sortedBy { it.name }
        val visiblePlayers = allPlayers.filter { !it.invisible }
        val visibleEntries = communicationHandler.buildWhoEntries(visiblePlayers, now)
        val allEntries = communicationHandler.buildWhoEntries(allPlayers, now)
        for (p in allPlayers) {
            gmcpEmitter.sendServerWho(p.sessionId, if (p.isStaff) allEntries else visibleEntries)
        }
    }

    /**
     * Handle a Session.Resume GMCP from a newly connected client attempting
     * to resume a suspended session via token.
     */
    private suspend fun handleSessionResume(newSessionId: SessionId, token: String) {
        val mgr = gracePeriodManager ?: return
        val suspended = mgr.resume(token)
        if (suspended == null) {
            gmcpEmitter.sendSessionResumeResult(newSessionId, false)
            return
        }

        val oldSessionId = suspended.sessionId
        log.info { "Resuming session: name=${suspended.playerState.name} old=$oldSessionId new=$newSessionId" }

        // Clear the pending login state for the new session (it was set in onConnected)
        loginFlowHandler.pendingLogins.remove(newSessionId)
        loginFlowHandler.failedLoginAttempts.remove(newSessionId)

        // Reattach the PlayerState to the new session
        players.resumeSession(oldSessionId, newSessionId, suspended.playerState)

        // Remap all subsystem state (combat, group, regen, etc.)
        sessionLifecycleCoordinator.remapSession(oldSessionId, newSessionId)

        // Restore GMCP session registrations
        gmcpSessions[newSessionId] = suspended.gmcpPackages.toMutableSet()

        // Notify client of success before full sync
        gmcpEmitter.sendSessionResumeResult(newSessionId, true)

        val me = players.get(newSessionId) ?: return
        outbound.send(OutboundEvent.SetAnsi(newSessionId, me.ansiEnabled))
        if (me.screenReaderEnabled) {
            outbound.send(OutboundEvent.SetScreenReader(newSessionId, true))
        }
        loginFlowHandler.onAfterLogin(newSessionId)

        // Full state sync (same as login)
        gmcpEmitter.sendFullCharacterSync(
            newSessionId,
            me,
            items,
            abilitySystem,
            statusEffectSystem,
            achievementRegistry,
            groupSystem,
            players,
            guildSystem,
            world,
        )
        // Dungeon catalog + lottery info are now emitted from onAfterLogin above
        // so they fire on every login path, including password/new-char logins.
        if (me.isStaff) {
            gmcpEmitter.sendStaffWorldInfo(newSessionId, world)
            gmcpEmitter.sendStaffMobTemplates(newSessionId, world)
        }
        router.handle(newSessionId, Command.Look)

        // Issue a new resume token for the next potential disconnect
        issueResumeToken(newSessionId)
    }

    /** Builds the current `World.Time` payload, including the active season. */
    private fun worldTimePayload(): GmcpEmitter.WorldTimePayload {
        val season = seasonSystem.season()
        return GmcpEmitter.WorldTimePayload(
            period = worldTimeSystem.period().name,
            hour = worldTimeSystem.gameHour(),
            minute = worldTimeSystem.gameMinute(),
            season = season.name,
            seasonDescription = season.description,
        )
    }

    /** Issue a resume token to the client after successful login/resume. */
    private suspend fun issueResumeToken(sessionId: SessionId) {
        val mgr = gracePeriodManager ?: return
        val token = mgr.issueToken(sessionId)
        gmcpEmitter.sendSessionResumeToken(sessionId, token, mgr.gracePeriodSeconds)
    }

    /**
     * Handle a Session.Authenticate GMCP from a client presenting a
     * remember-me auth token (stored in localStorage) for password-free login.
     *
     * [expectedName] (optional) is the character name the client believes the
     * token belongs to. It does not affect authentication (we always trust the
     * token hash), but lets us route the failure path straight to the password
     * prompt for that character instead of bouncing back to the name prompt.
     */
    private suspend fun handleSessionAuthenticate(sessionId: SessionId, token: String, expectedName: String?) {
        val hash = sha256Hex(token)
        val record = persistence.playerRepo?.findByAuthTokenHash(hash)
        if (record == null) {
            gmcpEmitter.sendSessionAuthResult(sessionId, false, "Invalid or expired token")
            promptForPasswordOrName(sessionId, expectedName)
            return
        }

        // Enforce server-side token expiry
        val tokenAgeMs = clock.millis() - record.authTokenIssuedAt
        val maxAgeMs = AUTH_TOKEN_EXPIRY_DAYS.toLong() * 24 * 60 * 60 * 1000
        if (record.authTokenIssuedAt > 0 && tokenAgeMs > maxAgeMs) {
            // Clear the expired token
            persistence.playerRepo?.save(record.copy(authTokenHash = "", authTokenIssuedAt = 0L))
            gmcpEmitter.sendSessionAuthResult(sessionId, false, "Token expired — please log in again")
            promptForPasswordOrName(sessionId, expectedName)
            return
        }

        log.info { "Auth token login: name=${record.name} sessionId=$sessionId" }

        // Clear the pending login state (set in onConnected)
        loginFlowHandler.pendingLogins.remove(sessionId)
        loginFlowHandler.failedLoginAttempts.remove(sessionId)

        // Cancel any grace period for this player
        gracePeriodManager?.cancelByName(record.name)?.let { cancelled ->
            sessionEventHandler.fullDisconnect(cancelled.sessionId, cancelled.playerState)
        }

        // Bind the session (handles takeover if already online)
        players.applyLoginCredentials(sessionId, record, record.ansiEnabled)

        // Verify the session bound successfully
        val me = players.get(sessionId)
        if (me == null) {
            gmcpEmitter.sendSessionAuthResult(sessionId, false, "Login failed")
            return
        }

        // The client already has the token it just presented, so there is no
        // need to rotate or re-send it here. Keeping the token stable avoids
        // races where a new Session.AuthToken message is lost (or processed
        // after the WebSocket dies on a flaky mobile connection), which
        // previously left localStorage pointing at a hash the server no longer
        // recognised and forced a password prompt on the next reconnect.
        gmcpEmitter.sendSessionAuthResult(sessionId, true)

        // Finalize login (same as normal flow)
        loginFlowHandler.onAfterLogin(sessionId)
        val player = players.get(sessionId) ?: return
        val raceAbilities = raceRegistry.get(player.race)?.abilities.orEmpty().toSet()
        abilitySystem.loadAbilities(sessionId, player.learnedAbilityIds, raceAbilities)
        outbound.send(OutboundEvent.SetAnsi(sessionId, player.ansiEnabled))
        if (player.screenReaderEnabled) {
            outbound.send(OutboundEvent.SetScreenReader(sessionId, true))
        }
        if (!world.rooms.containsKey(player.roomId)) {
            players.moveTo(sessionId, world.startRoom)
        }
        broadcastToRoom(players, outbound, player.roomId, "${player.name} enters.", sessionId)
        gmcpEmitter.sendFullCharacterSync(
            sessionId,
            player,
            items,
            abilitySystem,
            statusEffectSystem,
            achievementRegistry,
            groupSystem,
            players,
            guildSystem,
            world,
        )
        // Dungeon catalog + lottery info are now emitted from onAfterLogin above
        // so they fire on every login path, including password/new-char logins.
        if (player.isStaff) {
            gmcpEmitter.sendStaffWorldInfo(sessionId, world)
            gmcpEmitter.sendStaffMobTemplates(sessionId, world)
        }
        router.handle(sessionId, Command.Look)
    }

    /** Handle Session.Logout — clear the auth token and disconnect. */
    private suspend fun handleSessionLogout(sessionId: SessionId) {
        val me = players.get(sessionId) ?: return
        val pid = me.playerId ?: return
        val repo = persistence.playerRepo ?: return

        // Clear the auth token hash on both the live PlayerState and the
        // persisted record so the old token can't be reused.
        me.authTokenHash = ""
        me.authTokenIssuedAt = 0L
        val record = repo.findById(pid)
        if (record != null && record.authTokenHash.isNotEmpty()) {
            repo.save(record.copy(authTokenHash = "", authTokenIssuedAt = 0L))
        }

        log.info { "Player logged out (auth token cleared): name=${me.name}" }
        outbound.send(OutboundEvent.Close(sessionId, "Logged out."))
    }

    /**
     * After a failed token-based auto-relog, route the session straight to the
     * password prompt for the named character if it exists. Falls back to the
     * generic name prompt for unknown / invalid names so character existence
     * leaks no more than the normal login flow already does.
     */
    private suspend fun promptForPasswordOrName(sessionId: SessionId, expectedName: String?) {
        val name = expectedName?.trim().orEmpty()
        if (name.isNotEmpty() && players.isValidName(name) && players.hasRegisteredName(name)) {
            loginFlowHandler.pendingLogins[sessionId] = LoginState.AwaitingExistingPassword(name)
            loginFlowHandler.promptForExistingPassword(sessionId)
            return
        }
        loginFlowHandler.promptForName(sessionId)
    }

    /** Issue an auth token (remember-me) after successful login and persist the hash. */
    private suspend fun issueAuthToken(sessionId: SessionId) {
        val repo = persistence.playerRepo ?: return
        val me = players.get(sessionId) ?: return
        val pid = me.playerId ?: return
        val token = java.util.UUID.randomUUID().toString()
        val hash = sha256Hex(token)
        val issuedAt = clock.millis()
        val record = repo.findById(pid) ?: return
        repo.save(record.copy(authTokenHash = hash, authTokenIssuedAt = issuedAt))
        // Mirror the hash into the live PlayerState so subsequent persistIfClaimed
        // calls (on disconnect, etc.) don't erase it via toPlayerRecord().
        me.authTokenHash = hash
        me.authTokenIssuedAt = issuedAt
        gmcpEmitter.sendSessionAuthToken(sessionId, token, me.name, AUTH_TOKEN_EXPIRY_DAYS)
    }

    private suspend fun sendHousingGmcp(sessionId: SessionId) {
        val emitter = gmcpEmitter ?: return
        val hs = housingSystem
        if (hs == null) {
            emitter.sendHousingInfo(sessionId, hasHouse = false, available = false)
            return
        }
        val status = hs.houseStatus(sessionId)
        if (status != null) {
            emitter.sendHousingInfo(
                sessionId,
                hasHouse = true,
                ownerName = status.ownerName,
                rooms = status.rooms.map {
                    GmcpEmitter.HousingRoomPayload(
                        templateId = it.templateId,
                        title = it.title,
                        description = it.description,
                    )
                },
            )
        } else {
            emitter.sendHousingInfo(sessionId, hasHouse = false)
        }
    }

    suspend fun sendQuestListGmcp(sessionId: SessionId) {
        val ps = players.get(sessionId) ?: return
        val entries = ps.activeQuests.mapNotNull { (questId, state) ->
            val def = questRegistry.get(questId) ?: return@mapNotNull null
            QuestListEntry(
                id = questId,
                name = def.name,
                description = def.description,
                objectives = def.objectives.mapIndexed { idx, objDef ->
                    val prog = state.objectives.getOrNull(idx)
                    QuestObjectiveEntry(
                        description = objDef.description,
                        current = prog?.current ?: 0,
                        required = prog?.required ?: objDef.count,
                        targetRoomIds = resolveObjectiveRoomIds(objDef.targetId),
                    )
                },
                readyToTurnIn = questSystem.isReadyToTurnIn(def, state),
                giverMobId = def.giverMobId,
            )
        }
        gmcpEmitter.sendQuestList(sessionId, entries)
    }

    /**
     * Re-emits `Room.MobInfo` for the player's current room. Called when a quest event
     * may have flipped a mob's `questAvailable` / `questComplete` flag — the canvas
     * indicators and popout actions key off these flags and otherwise stay stale until
     * the player leaves and re-enters the room.
     */
    private suspend fun refreshRoomMobInfoForPlayer(sessionId: SessionId) {
        val ps = players.get(sessionId) ?: return
        val mobsInRoom = mobs.mobsInRoom(ps.roomId)
        val mobIds = mobsInRoom.map { it.id.value }
        val questAvailable = questSystem.questAvailableMobIds(sessionId, mobIds)
        val questComplete = questSystem.questCompleteMobIds(sessionId, mobIds)
        gmcpEmitter.sendRoomMobInfo(
            sessionId,
            gmcpEmitter.buildMobInfoEntries(
                mobsInRoom,
                questAvailableMobIds = questAvailable,
                questCompleteMobIds = questComplete,
            ),
        )
    }

    /**
     * Resolves a quest objective targetId (mob or item) to the room IDs where
     * that target spawns, for use as map markers on the client minimap.
     */
    private fun resolveObjectiveRoomIds(targetId: String): List<String> {
        val mobRooms = world.mobSpawns
            .filter { it.templateId.value == targetId }
            .map { it.roomId.value }
        if (mobRooms.isNotEmpty()) return mobRooms

        val itemRooms = world.itemSpawns
            .filter { it.instance.id.value == targetId }
            .mapNotNull { it.roomId?.value }
        return itemRooms
    }

    private suspend fun handleReloadCommand(
        mgr: HotReloadManager,
        target: String?,
    ): String {
        val result = when (target) {
            "world" -> mgr.reloadWorld()
            "abilities" -> mgr.reloadAbilities()
            "effects" -> mgr.reloadStatusEffects()
            else -> mgr.reloadAll()
        }
        return result.summary()
    }

    /**
     * Cleans up cross-system state when a player dies.
     * Unlike disconnect, the player remains connected — only active game state
     * that should not survive death is cleared.
     */
    private suspend fun cleanupOnPlayerDeath(sessionId: SessionId) {
        // End active trades (return escrowed items/gold)
        tradeSystem.cancelForPlayer(sessionId)

        // End active duels
        val endedDuel = duelSystem.endDuel(sessionId)
        if (endedDuel != null) {
            val other = if (endedDuel.player1 == sessionId) endedDuel.player2 else endedDuel.player1
            outbound.send(
                OutboundEvent.SendInfo(other, "Your duel opponent has died. Duel ended."),
            )
            gmcpEmitter?.sendDuelState(sessionId, active = false)
            gmcpEmitter?.sendDuelState(other, active = false)
        }

        // Dismiss active pets
        for (dismissed in petSystem.dismissAll(sessionId)) {
            broadcastPetRemoved(dismissed)
        }

        // Clear status effects (stop DOTs ticking on a corpse)
        statusEffectSystem.removeAllFromPlayer(sessionId)

        // Reset ability cooldowns
        abilitySystem.clearCooldowns(sessionId)

        // End active dialogue conversations
        dialogueSystem.endConversation(sessionId)

        // Leave group (dead players should not receive XP sharing)
        groupSystem.leave(sessionId)

        // Remove from dungeon instance and clear the panel so the client doesn't
        // keep showing Resume/Leave buttons that all error with "not in a dungeon".
        if (dungeonManager.removePlayer(sessionId) != null) {
            gmcpEmitter.sendDungeonInfo(sessionId, active = false)
        }
    }

    private suspend fun onCombatMobRemoved(
        mobId: MobId,
        roomId: RoomId,
    ) {
        // Check if this was a dungeon boss
        checkDungeonBossKilled(mobId)

        // Release any staff player possessing this mob
        adminHandler.releasePossessorOfPublic(mobId)
        mobRemovalCoordinator.onCombatKillCleanup(mobId)
        gmcpEmitter.broadcastRoomRemoveMob(roomId, mobId.value, players)
        val spawn = world.mobSpawns.find { it.id == mobId }
        val template = spawn?.let { world.mobTemplate(it.templateId) }
        val respawnMs = template?.respawnSeconds?.let { it * 1_000L }
        // Conditional mobs respawn through ConditionalSpawnHandler when their
        // gates next hold, not via the unconditional post-death timer.
        if (spawn != null && spawn.isConditional(world)) return
        if (spawn != null && template != null && respawnMs != null) {
            scheduler.scheduleIn(respawnMs) {
                if (mobs.get(spawn.id) != null) return@scheduleIn
                if (world.rooms[spawn.roomId] == null) return@scheduleIn
                val referenceLevel = highestPlayerLevelInZone(players, spawn.roomId.zone)
                val variant = world.mobTemplate(spawn.templateId)?.let { mobVariantRoller.roll(it) }
                val respawned = spawnToMobState(spawn, world, referenceLevel, variant)
                mobs.upsert(respawned)
                mobSystem.onMobSpawned(spawn.id)
                behaviorTreeSystem.onMobSpawned(spawn.id)
                for (p in players.playersInRoom(spawn.roomId)) {
                    outbound.send(OutboundEvent.SendText(p.sessionId, "${respawned.name} appears."))
                    gmcpEmitter.sendRoomAddMob(p.sessionId, respawned)
                }
                if (variant != null) announceMobVariant(outbound, players, respawned, variant.def)
            }
        }
    }

    private suspend fun onCombatLevelUp(
        sessionId: SessionId,
        result: LevelUpResult,
    ) {
        markVitalsDirty(sessionId)
        markStatsDirty(sessionId)
        val level = result.newLevel
        val p = players.get(sessionId)
        val autoLearned = p?.let { abilitySystem.recomputeKnownAbilities(sessionId, level, it.unlockedClasses) }.orEmpty()
        sendAutoLearnedAbilities(sessionId, autoLearned)
        val interval = engineConfig.skillPoints.interval
        val available = abilitySystem.availableSkillPoints(
            level = level,
            spentPoints = p?.let { abilitySystem.spentSkillPoints(it.learnedAbilityIds) } ?: 0,
            interval = interval,
            prestigeBonus = prestigeSystem.accumulatedSkillPointBonus(p?.prestigeLevel ?: 0),
        )
        if (available > 0) {
            val pointWord = if (available == 1) "skill point" else "skill points"
            outbound.send(
                OutboundEvent.SendText(
                    sessionId,
                    "You have $available $pointWord available! Visit a class trainer to learn new abilities.",
                ),
            )
        }
        if (p != null) {
            val hpStat = p.stats[engineConfig.stats.bindings.hpScalingStat]
            val manaStat = p.stats[engineConfig.stats.bindings.manaScalingStat]
            val (classHpPerLevel, classManaPerLevel) = progression.resolveClassScaling(p.playerClass)
            val newMaxHp = progression.maxHpForLevel(level, hpStat, classHpPerLevel)
            val oldMaxHp = progression.maxHpForLevel(result.previousLevel, hpStat, classHpPerLevel)
            val newMaxMana = progression.maxManaForLevel(level, manaStat, classManaPerLevel)
            val oldMaxMana = progression.maxManaForLevel(result.previousLevel, manaStat, classManaPerLevel)
            val hpGained = (newMaxHp - oldMaxHp).coerceAtLeast(0)
            val manaGained = (newMaxMana - oldMaxMana).coerceAtLeast(0)
            gmcpEmitter.sendCharName(sessionId, p)
            gmcpEmitter.sendCharSkills(
                sessionId,
                abilitySystem.knownAbilities(sessionId),
                cooldownRemainingMs = { abilityId -> abilitySystem.cooldownRemainingMs(sessionId, abilityId) },
                manaCostFor = { ability -> abilitySystem.computeManaCost(p, ability) },
            )
            gmcpEmitter.sendCharGain(
                sessionId,
                "levelUp",
                level.toLong(),
                newLevel = level,
                hpGained = hpGained,
                manaGained = manaGained,
            )
            gmcpEmitter.sendCharLevelUp(
                sessionId,
                previousLevel = result.previousLevel,
                newLevel = level,
                levelsGained = result.levelsGained.coerceAtLeast(1),
                hpGained = hpGained,
                manaGained = manaGained,
                newAbilities = autoLearned.map { it.displayName },
                skillPointsAvailable = available,
                isMilestone = isMilestoneLevel(level),
            )
            // Notify about new sprites if the player has a custom selection
            if (p.activeSprite != null) {
                notifyNewSprites(sessionId, p)
            }
            gmcpEmitter.sendCharSprites(sessionId, p)
        }
        achievementSystem.onLevelReached(sessionId, level)
    }

    /** Milestone levels trigger extra flourish on the client level-up banner. */
    private fun isMilestoneLevel(level: Int): Boolean = level == 10 || level == 25 || level == 50 || level == 100

    /** Sends a text hint when a level-up or achievement unlock makes new sprites available. */
    private suspend fun notifyNewSprites(
        sessionId: SessionId,
        player: PlayerState,
    ) {
        val reg = spriteRegistry ?: return
        val tierDefs = reg.unlockedDefinitions(
            level = player.level,
            unlockedAchievementIds = player.unlockedAchievementIds,
            isStaff = player.isStaff,
            playerRace = player.race,
            playerClass = player.playerClass,
        )
        // Only notify if there are more sprites than just the one they have selected
        if (tierDefs.size > 1) {
            outbound.send(
                OutboundEvent.SendText(
                    sessionId,
                    "New sprites are available! Use 'sprite list' to see your options.",
                ),
            )
        }
    }

    private suspend fun syncRoomItemsForRoom(roomId: RoomId) {
        gmcpEmitter.broadcastRoomItems(roomId, items.itemsInRoom(roomId), players)
    }

    /**
     * Notifies the daily/weekly quest system of a player action and sends
     * completion feedback if any quests were finished.
     */
    private suspend fun notifyDailyQuest(sessionId: SessionId, type: String) {
        val dqs = dailyQuestSystem ?: return
        val completed = dqs.onEvent(sessionId, type)
        if (completed.isEmpty()) return
        val beforeLevel = players.get(sessionId)?.level ?: return
        dqs.awardRewards(sessionId, completed)
        for (c in completed) {
            val label = if (c.isDaily) "Daily" else "Weekly"
            outbound.send(
                OutboundEvent.SendInfo(
                    sessionId,
                    "[$label Quest Complete] ${c.description} — ${c.goldReward}g, ${c.xpReward}xp",
                ),
            )
        }
        val afterPlayer = players.get(sessionId)
        val afterLevel = afterPlayer?.level ?: beforeLevel
        if (afterLevel > beforeLevel && afterPlayer != null) {
            onCombatLevelUp(
                sessionId,
                LevelUpResult(
                    previousLevel = beforeLevel,
                    levelsGained = afterLevel - beforeLevel,
                    newLevel = afterLevel,
                    xpTotal = afterPlayer.xpTotal,
                ),
            )
        }
        gmcpEmitter.sendDailyQuests(sessionId, dqs)
        gmcpEmitter.sendWeeklyQuests(sessionId, dqs)
    }

    private suspend fun sendAutoLearnedAbilities(
        sessionId: SessionId,
        abilities: List<dev.ambon.engine.abilities.AbilityDefinition>,
    ) {
        if (abilities.isEmpty()) return
        val names = abilities.joinToString { it.displayName }
        val verb = if (abilities.size == 1) "is" else "are"
        outbound.send(OutboundEvent.SendText(sessionId, "$names $verb now yours automatically."))
    }

    private suspend fun onCombatMobKilledByPlayer(
        sessionId: SessionId,
        templateKey: String,
    ) {
        // Skip quest/achievement callbacks for dead players (HP <= 0) — they may have
        // died in the same tick from a different mob.  Rewards should not fire posthumously.
        val player = players.get(sessionId)
        if (player == null || player.hp <= 0) return

        questSystem.onMobKilled(sessionId, templateKey)
        achievementSystem.onMobKilled(sessionId, templateKey)
        notifyDailyQuest(sessionId, "kill")
        autoQuestHandler.onMobKill(sessionId, templateKey)
        globalQuestSystem?.onEvent(sessionId, GlobalQuestObjectiveType.KILL)

        // Faction reputation changes on mob kill
        val mobTemplate = world.mobTemplate(dev.ambon.domain.ids.MobId(templateKey))
        if (mobTemplate?.faction != null) {
            val player = players.get(sessionId)
            if (player != null) {
                // Level proxy: maxHp/10, capped at 20 to prevent extreme swings from bosses
                val levelProxy = (mobTemplate.maxHp / 10).coerceAtMost(20)
                val changes = reputationSystem.onMobKilled(player, mobTemplate.faction, levelProxy)
                for (change in changes) {
                    val factionName = reputationSystem.getFaction(change.factionId)?.name ?: change.factionId
                    val sign = if (change.amount > 0) "+" else ""
                    outbound.send(
                        OutboundEvent.SendInfo(
                            sessionId,
                            "[Reputation] $factionName: $sign${change.amount} " +
                                "(${reputationSystem.tierLabel(change.newStanding)})",
                        ),
                    )
                }
                if (changes.isNotEmpty()) emitFactions(sessionId, player)
            }
        }
    }

    private suspend fun tickGlobalQuest(nowMs: Long) {
        val sys = globalQuestSystem ?: return
        val result = sys.tick(nowMs)
        when (result) {
            is GlobalQuestTickResult.Nothing -> {}
            is GlobalQuestTickResult.QuestStarted -> {
                for (p in players.allPlayers()) {
                    outbound.send(OutboundEvent.SendInfo(p.sessionId, "[GLOBAL QUEST] ${result.announcement}"))
                }
                val status = sys.getStatus()
                if (status != null) {
                    gmcpEmitter.broadcastGlobalQuest(status, players, emptyMap())
                }
            }
            is GlobalQuestTickResult.ProgressUpdate -> {
                for (p in players.allPlayers()) {
                    outbound.send(OutboundEvent.SendInfo(p.sessionId, "[GLOBAL QUEST] ${result.announcement}"))
                }
                val status = sys.getStatus()
                if (status != null) {
                    val progressMap = sys.activeQuestForTesting()?.progress ?: emptyMap()
                    gmcpEmitter.broadcastGlobalQuest(status, players, progressMap)
                }
            }
            is GlobalQuestTickResult.QuestEnded -> {
                for (p in players.allPlayers()) {
                    outbound.send(OutboundEvent.SendInfo(p.sessionId, "[GLOBAL QUEST] ${result.announcement}"))
                }
                for (winner in result.winners) {
                    val ps = players.get(winner.sessionId) ?: continue
                    ps.gold += winner.goldReward
                    val levelResult = progression.grantXp(ps, winner.xpReward)
                    markVitalsDirty(winner.sessionId)
                    val rewardMsg = "You earned ${winner.goldReward} gold and ${winner.xpReward} XP " +
                        "for placing ${ordinalPlace(winner.place)} in the global quest!"
                    outbound.send(OutboundEvent.SendInfo(winner.sessionId, rewardMsg))
                    if (levelResult.levelsGained > 0) {
                        onCombatLevelUp(winner.sessionId, levelResult)
                    }
                }
                gmcpEmitter.broadcastGlobalQuestInactive(players)
            }
        }
    }

    private fun ordinalPlace(n: Int): String = when (n) {
        1 -> "1st"
        2 -> "2nd"
        3 -> "3rd"
        else -> "${n}th"
    }

    /**
     * Broadcasts `Room.RemoveMob` and a refreshed `Room.MobInfo` to every player in the
     * pet's room so the canvas / sidebar mob list updates immediately when a pet is
     * dismissed (manual command, duration expiry, owner disconnect, pet death).
     * Mirrors the summon-side broadcast added in #1067 — see issue #1069.
     */
    private suspend fun broadcastPetRemoved(dismissed: PetSystem.DismissedPet) {
        gmcpEmitter.broadcastRoomRemoveMob(dismissed.roomId, dismissed.petId.value, players)
        val mobsInRoom = mobs.mobsInRoom(dismissed.roomId)
        val mobInfoEntries = gmcpEmitter.buildMobInfoEntries(mobsInRoom)
        gmcpEmitter.broadcastRoomMobInfo(dismissed.roomId, mobInfoEntries, players)
    }

    private suspend fun emitPetState(sessionId: SessionId, pet: dev.ambon.domain.mob.MobState?) {
        gmcpEmitter.sendPetState(
            sessionId,
            if (pet != null) {
                GmcpEmitter.PetStatePayload(
                    active = true,
                    name = pet.name,
                    hp = pet.hp,
                    maxHp = pet.maxHp,
                    minDamage = pet.damage.min,
                    maxDamage = pet.damage.max,
                    armor = pet.armor,
                    image = pet.image,
                )
            } else {
                GmcpEmitter.PetStatePayload(
                    active = false,
                    name = null,
                    hp = null,
                    maxHp = null,
                    minDamage = null,
                    maxDamage = null,
                    armor = null,
                    image = null,
                )
            },
        )
        emitPetSkills(sessionId, pet)
    }

    /**
     * Emits the active pet's signature skills (or an empty list if [pet] is null) with current
     * cooldown remaining. Mapped effect types align with the player ability vocabulary so the
     * web spellbook can categorize pet skills the same way as player abilities.
     */
    private suspend fun emitPetSkills(sessionId: SessionId, pet: dev.ambon.domain.mob.MobState?) {
        if (pet == null) {
            gmcpEmitter.sendPetSkills(sessionId, emptyList())
            return
        }
        val now = clock.millis()
        val payloads = pet.spells.map { spell ->
            GmcpEmitter.PetSkillPayload(
                id = spell.id,
                name = spell.displayName,
                description = "",
                cooldownMs = spell.cooldownMs,
                cooldownRemainingMs = combatSystem.petSkillCooldownRemainingMs(pet.id, spell.id, now),
                effectType = mapPetSkillEffectType(spell),
                image = spell.image,
                petName = pet.name,
            )
        }
        gmcpEmitter.sendPetSkills(sessionId, payloads)
    }

    private fun mapPetSkillEffectType(spell: dev.ambon.domain.mob.MobSpell): String =
        when {
            spell.threatBonus > 0.0 && spell.damage == null -> "TAUNT"
            spell.damage != null -> "DIRECT_DAMAGE"
            spell.statusEffectId != null -> "APPLY_STATUS"
            else -> "DIRECT_DAMAGE"
        }

    private suspend fun emitFactions(sessionId: SessionId, player: PlayerState) {
        val definitions = reputationSystem.factionDefinitions()
        val standings = reputationSystem.allStandings(player)
        gmcpEmitter.sendCharFactions(
            sessionId,
            standings.map { (factionId, reputation) ->
                GmcpEmitter.FactionStandingPayload(
                    id = factionId,
                    name = definitions[factionId]?.name ?: factionId,
                    reputation = reputation,
                    tier = reputationSystem.tierLabel(reputation),
                )
            },
        )
    }

    private suspend fun emitCurrencies(sessionId: SessionId, player: PlayerState) {
        val definitions = currencySystem.definitions()
        gmcpEmitter.sendCharCurrencies(
            sessionId,
            definitions.map { (currencyId, def) ->
                GmcpEmitter.CurrencyBalancePayload(
                    id = currencyId,
                    name = def.displayName,
                    abbreviation = def.abbreviation,
                    balance = currencySystem.balance(player, currencyId),
                )
            },
        )
    }

    private suspend fun emitDungeonCatalog(sessionId: SessionId) {
        gmcpEmitter.sendDungeonCatalog(
            sessionId,
            dungeonRegistry.all()
                .sortedWith(compareBy({ it.minLevel }, { it.name }))
                .map { template ->
                    GmcpEmitter.DungeonCatalogEntryPayload(
                        id = template.id,
                        name = template.name,
                        description = template.description,
                        minLevel = template.minLevel,
                        portalHint = buildDungeonPortalHint(template),
                        difficulties = DungeonDifficulty.entries.map { difficulty ->
                            GmcpEmitter.DungeonCatalogDifficultyPayload(
                                id = difficulty.name.lowercase(),
                                label = difficulty.displayName,
                                summary = dungeonDifficultySummary(difficulty),
                            )
                        },
                    )
                },
        )
    }

    private suspend fun emitLotteryInfo(sessionId: SessionId, playerName: String) {
        val info = lotterySystem.getInfo(playerName)
        gmcpEmitter.sendLotteryInfo(sessionId, info)
    }

    private fun buildDungeonPortalHint(template: dev.ambon.domain.dungeon.DungeonTemplateDef): String? {
        val portalLocalId = template.portalRoom ?: return null
        val portalRoomId = RoomId("${template.id.substringBefore(':')}:$portalLocalId")
        val portalRoom = world.rooms[portalRoomId] ?: return null
        val outsideRoom = portalRoom.exits.values
            .firstOrNull { exit -> exit.zone != portalRoomId.zone }
            ?.let(world.rooms::get)
        return when {
            outsideRoom != null -> "Access from ${outsideRoom.title} through ${portalRoom.title}."
            else -> "Access via ${portalRoom.title}."
        }
    }

    private fun dungeonDifficultySummary(difficulty: DungeonDifficulty): String =
        when (difficulty) {
            DungeonDifficulty.LORE -> "Low-risk sightseeing run with little reward."
            DungeonDifficulty.NORMAL -> "Standard dungeon pacing and rewards."
            DungeonDifficulty.HARD -> "Heavier damage, tougher mobs, better rewards."
            DungeonDifficulty.HEROIC -> "Maximum challenge with top-end loot and XP."
        }

    /** Schedules a leaderboard refresh, then re-schedules itself for the next interval. */
    private suspend fun scheduleLeaderboardRefresh(sys: LeaderboardSystem) {
        scheduler.scheduleIn(sys.refreshIntervalMs) {
            sys.refresh()
            scheduleLeaderboardRefresh(sys)
        }
        // Run the first refresh immediately on the first tick so the cache is populated at startup.
        sys.refresh()
    }

    private suspend fun checkDungeonBossKilled(mobId: MobId) {
        val inst = dungeonManager.findInstanceByBossMob(mobId)
        if (inst == null || inst.completed) return
        dungeonManager.markComplete(inst)
        metrics.onGameEvent("dungeon", "completed")
        val completionHeadline =
            "** The dungeon boss has been defeated! The ${inst.template.name} is complete! **"
        for (sid in inst.members) {
            players.get(sid)?.let { it.dungeonsCompleted += 1 }
            achievementSystem.onDungeonCompleted(sid, inst.template.name)
            notifyDailyQuest(sid, "dungeon")
            if (inst.members.size >= engineConfig.group.maxSize) {
                achievementSystem.onDungeonCompletedWithFullParty(sid, inst.template.name)
            }
            outbound.send(OutboundEvent.SendInfo(sid, completionHeadline))
            val lootTable = inst.template.lootTables[inst.difficulty]
            if (lootTable != null) {
                for (rewardId in lootTable.completionRewards) {
                    val item = items.createFromTemplate(rewardId)
                    if (item != null) {
                        items.addToInventory(sid, item)
                        outbound.send(
                            OutboundEvent.SendInfo(sid, "You receive: ${item.item.displayName}"),
                        )
                    }
                }
            }
            outbound.send(
                OutboundEvent.SendInfo(sid, "Type 'dungeon leave' to return to the portal."),
            )
            // Update dungeon panel state so the UI shows completion instead of the stale
            // "You enter <name>" message (see issue #1041).
            gmcpEmitter.sendDungeonInfo(
                sid,
                active = true,
                instanceId = inst.id,
                name = inst.template.name,
                difficulty = inst.difficulty.displayName,
                totalRooms = inst.layout.rooms.size,
                completed = true,
                memberCount = inst.members.size,
            )
            gmcpEmitter.sendUiFeedback(
                sessionId = sid,
                type = "success",
                message = "${inst.template.name} complete! Type 'dungeon leave' to return to the portal.",
                code = "COMPLETED",
                scope = "dungeon",
                command = "complete",
            )
        }
    }

    /**
     * Resolves one round of duel combat for all active duels.
     * Each player attacks their opponent once per tick (2 seconds).
     * The loser's HP is set to 1 (not killed). Duel ends immediately.
     */
    private suspend fun tickDuels() {
        val now = clock.millis()
        val duelTickIntervalMs = 2000L

        for (duel in duelSystem.activeDuels()) {
            if (now - duel.lastTickedAtMs < duelTickIntervalMs) continue
            duel.lastTickedAtMs = now

            val p1 = players.get(duel.player1) ?: continue
            val p2 = players.get(duel.player2) ?: continue

            // Player 1 attacks Player 2
            resolveDuelAttack(p1, p2, duel.player1, duel.player2)

            // Check if P2 is defeated
            if (p2.hp <= 1) {
                endDuelWithResult(duel, winner = duel.player1, loser = duel.player2)
                continue
            }

            // Player 2 attacks Player 1
            resolveDuelAttack(p2, p1, duel.player2, duel.player1)

            // Check if P1 is defeated
            if (p1.hp <= 1) {
                endDuelWithResult(duel, winner = duel.player2, loser = duel.player1)
            }
        }

        duelSystem.expireChallenges()
    }

    private suspend fun resolveDuelAttack(
        attacker: PlayerState,
        defender: PlayerState,
        attackerSid: SessionId,
        defenderSid: SessionId,
    ) {
        val attackerStats = attacker.stats
        val defenderStats = defender.stats

        // Dodge check (same formula as mob combat)
        val dodgePct = ((defenderStats["DEX"] - 10) * 2).coerceIn(0, 30)
        if (dodgePct > 0 && duelRng.nextInt(100) < dodgePct) {
            outbound.send(OutboundEvent.SendText(attackerSid, "${defender.name} dodges your attack!"))
            outbound.send(OutboundEvent.SendText(defenderSid, "You dodge ${attacker.name}'s attack!"))
            return
        }

        // Same melee formula as PvE/PvP basic attacks — see computePlayerMeleeSwing.
        val eqAttack = items.equipmentBonuses(attackerSid).attack
        val defArmor = items.equipmentBonuses(defenderSid).armor
        val swing = computePlayerMeleeSwing(
            bindings = engineConfig.stats.bindings,
            level = attacker.level,
            stats = attackerStats,
            equipAttack = eqAttack,
            enemyArmor = defArmor,
            rng = duelRng,
        )
        val damage = swing.final

        // Apply damage (clamp to 1 HP minimum — duels don't kill)
        defender.hp = (defender.hp - damage).coerceAtLeast(1)

        outbound.send(
            OutboundEvent.SendText(
                attackerSid,
                "You hit ${defender.name} for $damage damage. (${defender.hp}/${defender.maxHp} HP)",
            ),
        )
        outbound.send(
            OutboundEvent.SendText(
                defenderSid,
                "${attacker.name} hits you for $damage damage! (${defender.hp}/${defender.maxHp} HP)",
            ),
        )
        markVitalsDirty(attackerSid)
        markVitalsDirty(defenderSid)
    }

    private suspend fun endDuelWithResult(
        duel: ActiveDuel,
        winner: SessionId,
        loser: SessionId,
    ) {
        duelSystem.endDuel(winner)
        val winnerName = players.get(winner)?.name ?: "Someone"
        val loserName = players.get(loser)?.name ?: "Someone"

        outbound.send(
            OutboundEvent.SendInfo(winner, "** You have defeated $loserName in a duel! **"),
        )
        outbound.send(
            OutboundEvent.SendInfo(loser, "** You have been defeated by $winnerName! **"),
        )

        val roomId = players.get(winner)?.roomId
        if (roomId != null) {
            broadcastToRoom(
                players,
                outbound,
                roomId,
                "** $winnerName defeats $loserName in a duel! **",
                winner,
                loser,
            )
        }
        gmcpEmitter?.sendDuelState(winner, active = false)
        gmcpEmitter?.sendDuelState(loser, active = false)
    }
}

/**
 * Snapshot the static area catalog used by the `World.Areas` GMCP package.
 * Computed once at engine init from the loaded [World]; zones with no level
 * signal pass through with null bounds so the client can show them as "—".
 */
private fun buildWorldAreaPayloads(world: dev.ambon.domain.world.World): List<WorldAreaPayload> {
    val ranges = dev.ambon.engine.commands.handlers.computeWorldZoneLevelRanges(world)
    return ranges
        .map { (zone, range) -> WorldAreaPayload(zone = zone, minLevel = range?.first, maxLevel = range?.last) }
        .sortedWith(compareBy({ it.minLevel ?: Int.MAX_VALUE }, { it.zone }))
}
