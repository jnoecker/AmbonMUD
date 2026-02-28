package dev.ambon.test

import dev.ambon.bus.LocalInboundBus
import dev.ambon.bus.LocalOutboundBus
import dev.ambon.config.EconomyConfig
import dev.ambon.domain.PlayerClass
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.World
import dev.ambon.domain.world.load.WorldLoader
import dev.ambon.engine.CombatSystem
import dev.ambon.engine.GameEngine
import dev.ambon.engine.GroupSystem
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.PasswordHasher
import dev.ambon.engine.PlayerProgression
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.PhaseResult
import dev.ambon.engine.commands.buildTestRouter
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

object TestWorlds {
    val testWorld: World by lazy { WorldLoader.loadFromResource("world/test_world.yaml") }
    val okSmall: World by lazy { WorldLoader.loadFromResource("world/ok_small.yaml") }
    val okFeatures: World by lazy { WorldLoader.loadFromResource("world/ok_features.yaml") }
}

object TestPasswordHasher : PasswordHasher {
    private const val PREFIX = "test-hash:"

    override fun hash(password: String): String = PREFIX + password

    override fun verify(
        password: String,
        passwordHash: String,
    ): Boolean = passwordHash == hash(password)
}

fun buildTestPlayerRegistry(
    startRoom: RoomId,
    repo: PlayerRepository = InMemoryPlayerRepository(),
    items: ItemRegistry = ItemRegistry(),
    clock: Clock = Clock.systemUTC(),
    progression: PlayerProgression = PlayerProgression(),
    hashingContext: CoroutineContext = EmptyCoroutineContext,
    passwordHasher: PasswordHasher = TestPasswordHasher,
    classStartRooms: Map<PlayerClass, RoomId> = emptyMap(),
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
            onMobSmited: (MobId) -> Unit = {},
            interEngineBus: InterEngineBus? = null,
            playerLocationIndex: PlayerLocationIndex? = null,
            engineId: String = "",
            onRemoteWho: (suspend (SessionId) -> Unit)? = null,
            onPhase: (suspend (SessionId, String?) -> PhaseResult)? = null,
            onCrossZoneMove: (suspend (SessionId, RoomId) -> Unit)? = null,
            economyConfig: EconomyConfig = EconomyConfig(),
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
                    onMobSmited = onMobSmited,
                    interEngineBus = interEngineBus,
                    playerLocationIndex = playerLocationIndex,
                    engineId = engineId,
                    onRemoteWho = onRemoteWho,
                    onPhase = onPhase,
                    onCrossZoneMove = onCrossZoneMove,
                    economyConfig = economyConfig,
                )
            return CommandRouterHarness(world, repo, items, players, mobs, outbound, progression, groupSystem, combat, router)
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
        ): GameEngineHarness {
            val players = buildTestPlayerRegistry(world.startRoom, repo, items, clock = clock, progression = progression)
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
                )
            val engineJob = scope.launch { engine.run() }
            return GameEngineHarness(world, repo, inbound, outbound, items, players, mobs, clock, scheduler, tickMillis, engine, engineJob)
        }
    }
}
