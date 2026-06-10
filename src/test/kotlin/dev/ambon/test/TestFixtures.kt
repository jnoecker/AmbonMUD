package dev.ambon.test

import dev.ambon.bus.LocalInboundBus
import dev.ambon.bus.LocalOutboundBus
import dev.ambon.config.BankConfig
import dev.ambon.config.ClassDefinitionConfig
import dev.ambon.config.ClassEngineConfig
import dev.ambon.config.EconomyConfig
import dev.ambon.config.EngineConfig
import dev.ambon.config.RaceDefinitionConfig
import dev.ambon.config.RaceEngineConfig
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.World
import dev.ambon.domain.world.load.WorldLoader
import dev.ambon.engine.CombatSystem
import dev.ambon.engine.DuelSystem
import dev.ambon.engine.GameEngine
import dev.ambon.engine.GmcpEmitter
import dev.ambon.engine.GroupSystem
import dev.ambon.engine.HousingSystem
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.MobRemovalCoordinator
import dev.ambon.engine.PasswordHasher
import dev.ambon.engine.PetSystem
import dev.ambon.engine.PlayerClassRegistry
import dev.ambon.engine.PlayerClassRegistryLoader
import dev.ambon.engine.PlayerProgression
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.RaceRegistry
import dev.ambon.engine.RaceRegistryLoader
import dev.ambon.engine.StatRegistry
import dev.ambon.engine.TradeSystem
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.PhaseResult
import dev.ambon.engine.commands.buildTestRouter
import dev.ambon.engine.crafting.EnchantSystem
import dev.ambon.engine.crafting.GatheringRegistry
import dev.ambon.engine.dungeon.DungeonManager
import dev.ambon.engine.dungeon.DungeonRegistry
import dev.ambon.engine.events.InboundEvent
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.engine.scheduler.Scheduler
import dev.ambon.metrics.GameMetrics
import dev.ambon.persistence.InMemoryPlayerRepository
import dev.ambon.persistence.PlayerCreationRequest
import dev.ambon.persistence.PlayerRepository
import dev.ambon.sharding.InterEngineBus
import dev.ambon.sharding.PlayerLocationIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.Clock
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** Common session ID for single-player tests. */
val TEST_SESSION_ID = SessionId(1L)

/** Common room ID for tests that need a generic `zone:room`. */
val TEST_ROOM_ID = RoomId("zone:room")

object TestWorlds {
    val testWorld: World by lazy { WorldLoader.loadFromResource("world/test_world.yaml") }
    val okSmall: World by lazy { WorldLoader.loadFromResource("world/ok_small.yaml") }
    val okFeatures: World by lazy { WorldLoader.loadFromResource("world/ok_features.yaml") }
    val okPuzzles: World by lazy { WorldLoader.loadFromResource("world/ok_puzzles.yaml") }
    val okAchievementGate: World by lazy { WorldLoader.loadFromResource("world/ok_achievement_gate.yaml") }
    val okFleeGate: World by lazy { WorldLoader.loadFromResource("world/ok_flee_gate.yaml") }
    val okTimedRespawn: World by lazy { WorldLoader.loadFromResource("world/ok_timed_respawn.yaml") }
}

object TestPasswordHasher : PasswordHasher {
    private const val PREFIX = "test-hash:"

    override fun hash(password: String): String = PREFIX + password

    override fun verify(
        password: String,
        passwordHash: String,
    ): Boolean = passwordHash == hash(password)
}

/** Standard class definitions used across tests. Matches the old hardcoded defaults. */
fun testClassEngineConfig(): ClassEngineConfig =
    ClassEngineConfig(
        definitions = mapOf(
            "WARRIOR" to ClassDefinitionConfig(
                displayName = "Warrior",
                hpScalingRate = 1.80,
                manaScalingRate = 1.20,
                primaryStat = "STR",
                threatMultiplier = 1.5,
            ),
            "MAGE" to ClassDefinitionConfig(
                displayName = "Mage",
                hpScalingRate = 1.40,
                manaScalingRate = 1.80,
                primaryStat = "INT",
            ),
            "CLERIC" to ClassDefinitionConfig(
                displayName = "Cleric",
                hpScalingRate = 1.60,
                manaScalingRate = 1.60,
                primaryStat = "WIS",
            ),
            "ROGUE" to ClassDefinitionConfig(
                displayName = "Rogue",
                hpScalingRate = 1.50,
                manaScalingRate = 1.40,
                primaryStat = "DEX",
            ),
            "SWARM" to ClassDefinitionConfig(
                displayName = "Swarm",
                hpScalingRate = 1.20,
                manaScalingRate = 1.15,
                selectable = false,
            ),
        ),
    )

/** Standard race definitions used across tests. Matches the old hardcoded defaults. */
fun testRaceEngineConfig(): RaceEngineConfig =
    RaceEngineConfig(
        definitions = mapOf(
            "HUMAN" to RaceDefinitionConfig(
                displayName = "Human",
                statMods = mapOf("STR" to 1, "CHA" to 1),
            ),
            "ELF" to RaceDefinitionConfig(
                displayName = "Elf",
                statMods = mapOf("STR" to -1, "DEX" to 2, "CON" to -2, "INT" to 1),
            ),
            "DWARF" to RaceDefinitionConfig(
                displayName = "Dwarf",
                statMods = mapOf("STR" to 1, "DEX" to -1, "CON" to 2, "WIS" to 1, "CHA" to -2),
            ),
            "HALFLING" to RaceDefinitionConfig(
                displayName = "Halfling",
                statMods = mapOf("STR" to -2, "DEX" to 2, "CON" to -1, "WIS" to 1, "CHA" to 1),
            ),
        ),
    )

fun buildTestPlayerRegistry(
    startRoom: RoomId,
    repo: PlayerRepository = InMemoryPlayerRepository(),
    items: ItemRegistry = ItemRegistry(),
    clock: Clock = Clock.systemUTC(),
    progression: PlayerProgression = PlayerProgression(),
    hashingContext: CoroutineContext = EmptyCoroutineContext,
    passwordHasher: PasswordHasher = TestPasswordHasher,
    classStartRooms: Map<String, RoomId> = emptyMap(),
    classRegistry: PlayerClassRegistry? = null,
    raceRegistry: RaceRegistry? = null,
    statRegistry: StatRegistry? = null,
    startingGold: Long = 0L,
): PlayerRegistry =
    PlayerRegistry(
        startRoom = startRoom,
        classStartRooms = classStartRooms,
        repo = repo,
        items = items,
        clock = clock,
        progression = progression,
        hashingContext = hashingContext,
        passwordHasher = passwordHasher,
        classRegistry = classRegistry,
        raceRegistry = raceRegistry,
        statRegistry = statRegistry,
        startingGold = startingGold,
    )

suspend fun InMemoryPlayerRepository.createTestPlayer(
    name: String,
    roomId: RoomId,
    password: String = "password",
    nowEpochMs: Long = 0L,
    ansiEnabled: Boolean = false,
) = create(
    PlayerCreationRequest(
        name = name,
        startRoomId = roomId,
        nowEpochMs = nowEpochMs,
        passwordHash = TestPasswordHasher.hash(password),
        ansiEnabled = ansiEnabled,
    ),
)

class CommandRouterHarness private constructor(
    val world: World,
    val repo: InMemoryPlayerRepository,
    val items: ItemRegistry,
    val players: PlayerRegistry,
    val mobs: MobRegistry,
    val outbound: LocalOutboundBus,
    val progression: PlayerProgression,
    val groupSystem: GroupSystem?,
    val combat: CombatSystem,
    val router: CommandRouter,
    val duelSystem: DuelSystem? = null,
    val tradeSystem: TradeSystem? = null,
) {
    suspend fun loginPlayer(
        sessionId: SessionId,
        name: String,
        password: String = "password",
    ) {
        players.loginOrFail(sessionId, name, password)
    }

    suspend fun loginStaff(
        sessionId: SessionId,
        name: String,
        password: String = "password",
    ) {
        loginPlayer(sessionId, name, password)
        players.get(sessionId)!!.isStaff = true
    }

    fun drain() = outbound.drainAll()

    companion object {
        fun create(
            world: World = TestWorlds.testWorld,
            repo: InMemoryPlayerRepository = InMemoryPlayerRepository(),
            items: ItemRegistry = ItemRegistry(),
            players: PlayerRegistry = buildTestPlayerRegistry(world.startRoom, repo, items),
            mobs: MobRegistry = MobRegistry(),
            outbound: LocalOutboundBus = LocalOutboundBus(),
            progression: PlayerProgression = PlayerProgression(),
            groupSystem: GroupSystem? = null,
            onShutdown: suspend () -> Unit = {},
            mobRemovalCoordinator: MobRemovalCoordinator? = null,
            interEngineBus: InterEngineBus? = null,
            playerLocationIndex: PlayerLocationIndex? = null,
            engineId: String = "",
            onRemoteWho: (suspend (SessionId) -> Unit)? = null,
            onPhase: (suspend (SessionId, String?) -> PhaseResult)? = null,
            onCrossZoneMove: (suspend (SessionId, RoomId) -> Unit)? = null,
            economyConfig: EconomyConfig = EconomyConfig(),
            clock: Clock = Clock.systemUTC(),
            gatheringRegistry: GatheringRegistry? = null,
            petSystem: PetSystem? = null,
            enchantSystem: EnchantSystem? = null,
            bankConfig: BankConfig? = null,
            stylistConfig: dev.ambon.config.StylistConfig? = null,
            raceRegistry: dev.ambon.engine.RaceRegistry? = null,
            classRegistry: dev.ambon.engine.PlayerClassRegistry? = null,
            genderRegistry: dev.ambon.engine.GenderRegistry? = null,
            duelSystem: DuelSystem? = null,
            tradeSystem: TradeSystem? = null,
            dungeonManager: DungeonManager? = null,
            dungeonRegistry: DungeonRegistry? = null,
            housingSystem: HousingSystem? = null,
            gmcpEmitter: GmcpEmitter? = null,
            abilitySystem: dev.ambon.engine.abilities.AbilitySystem? = null,
            spriteRegistry: dev.ambon.engine.SpriteRegistry? = null,
            deathConfig: dev.ambon.config.DeathConfig = dev.ambon.config.DeathConfig(),
        ): CommandRouterHarness {
            val combat = CombatSystem(players, mobs, items, outbound)
            val router =
                buildTestRouter(
                    world = world,
                    players = players,
                    mobs = mobs,
                    items = items,
                    combat = combat,
                    outbound = outbound,
                    progression = progression,
                    groupSystem = groupSystem,
                    onShutdown = onShutdown,
                    mobRemovalCoordinator = mobRemovalCoordinator,
                    interEngineBus = interEngineBus,
                    playerLocationIndex = playerLocationIndex,
                    engineId = engineId,
                    onRemoteWho = onRemoteWho,
                    onPhase = onPhase,
                    onCrossZoneMove = onCrossZoneMove,
                    economyConfig = economyConfig,
                    clock = clock,
                    gatheringRegistry = gatheringRegistry,
                    petSystem = petSystem,
                    enchantSystem = enchantSystem,
                    bankConfig = bankConfig,
                    stylistConfig = stylistConfig,
                    raceRegistry = raceRegistry,
                    classRegistry = classRegistry,
                    genderRegistry = genderRegistry,
                    duelSystem = duelSystem,
                    tradeSystem = tradeSystem,
                    dungeonManager = dungeonManager,
                    dungeonRegistry = dungeonRegistry,
                    housingSystem = housingSystem,
                    gmcpEmitter = gmcpEmitter,
                    abilitySystem = abilitySystem,
                    spriteRegistry = spriteRegistry,
                    deathConfig = deathConfig,
                )
            return CommandRouterHarness(
                world = world,
                repo = repo,
                items = items,
                players = players,
                mobs = mobs,
                outbound = outbound,
                progression = progression,
                groupSystem = groupSystem,
                combat = combat,
                router = router,
                duelSystem = duelSystem,
                tradeSystem = tradeSystem,
            )
        }
    }
}

class GameEngineHarness private constructor(
    val world: World,
    val repo: InMemoryPlayerRepository,
    val inbound: LocalInboundBus,
    val outbound: LocalOutboundBus,
    val items: ItemRegistry,
    val players: PlayerRegistry,
    val mobs: MobRegistry,
    val clock: Clock,
    val scheduler: Scheduler,
    val tickMillis: Long,
    val engine: GameEngine,
    val engineJob: Job,
) {
    fun drain(): List<OutboundEvent> = outbound.drainAll()

    /**
     * Drives a fresh player through the engine's full login sequence
     * (Connected → name → "yes" → password → race → class) and drains the resulting output.
     */
    suspend fun loginNewPlayer(
        sid: SessionId,
        name: String,
        password: String = "password",
        race: String = "1",
        playerClass: String = "1",
    ) {
        inbound.send(InboundEvent.Connected(sid))
        inbound.send(InboundEvent.LineReceived(sid, name))
        inbound.send(InboundEvent.LineReceived(sid, "yes"))
        inbound.send(InboundEvent.LineReceived(sid, password))
        inbound.send(InboundEvent.LineReceived(sid, race))
        inbound.send(InboundEvent.LineReceived(sid, playerClass))
    }

    fun close() {
        engineJob.cancel()
        inbound.close()
        outbound.close()
    }

    companion object {
        fun start(
            scope: CoroutineScope,
            world: World = TestWorlds.testWorld,
            repo: InMemoryPlayerRepository = InMemoryPlayerRepository(),
            inbound: LocalInboundBus = LocalInboundBus(),
            outbound: LocalOutboundBus = LocalOutboundBus(),
            items: ItemRegistry = ItemRegistry(),
            clock: Clock,
            tickMillis: Long = 10L,
            progression: PlayerProgression = PlayerProgression(),
            metrics: GameMetrics = GameMetrics.noop(),
            engineConfig: EngineConfig = EngineConfig(),
        ): GameEngineHarness {
            val classRegistry =
                PlayerClassRegistry().also { reg ->
                    PlayerClassRegistryLoader.load(testClassEngineConfig(), reg)
                }
            val raceRegistry =
                RaceRegistry().also { reg ->
                    RaceRegistryLoader.load(testRaceEngineConfig(), reg)
                }
            val players =
                buildTestPlayerRegistry(
                    world.startRoom,
                    repo,
                    items,
                    clock = clock,
                    progression = progression,
                    classRegistry = classRegistry,
                    raceRegistry = raceRegistry,
                )
            val mobs = MobRegistry()
            val scheduler = Scheduler(clock)
            val engine =
                GameEngine(
                    inbound = inbound,
                    outbound = outbound,
                    players = players,
                    world = world,
                    clock = clock,
                    tickMillis = tickMillis,
                    scheduler = scheduler,
                    mobs = mobs,
                    items = items,
                    metrics = metrics,
                    engineConfig = engineConfig,
                    classRegistryOverride = classRegistry,
                    raceRegistryOverride = raceRegistry,
                )
            val engineJob = scope.launch { engine.run() }
            return GameEngineHarness(world, repo, inbound, outbound, items, players, mobs, clock, scheduler, tickMillis, engine, engineJob)
        }
    }
}
