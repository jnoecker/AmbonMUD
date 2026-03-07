package dev.ambon

import dev.ambon.admin.AdminHttpServer
import dev.ambon.bus.InboundBus
import dev.ambon.bus.LocalInboundBus
import dev.ambon.bus.LocalOutboundBus
import dev.ambon.bus.OutboundBus
import dev.ambon.bus.RedisInboundBus
import dev.ambon.bus.RedisOutboundBus
import dev.ambon.bus.redisBusPublisher
import dev.ambon.bus.redisBusSubscriberSetup
import dev.ambon.config.AppConfig
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.world.WorldFactory
import dev.ambon.engine.GameEngine
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.PersistenceContext
import dev.ambon.engine.PlayerClassRegistry
import dev.ambon.engine.PlayerClassRegistryLoader
import dev.ambon.engine.PlayerProgression
import dev.ambon.engine.RaceRegistry
import dev.ambon.engine.RaceRegistryLoader
import dev.ambon.engine.StatRegistry
import dev.ambon.engine.StatRegistryLoader
import dev.ambon.engine.WorldStateRegistry
import dev.ambon.engine.createPlayerRegistry
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.engine.scheduler.Scheduler
import dev.ambon.metrics.GameMetrics
import dev.ambon.persistence.GuildRepositoryFactory
import dev.ambon.persistence.PersistenceWorker
import dev.ambon.persistence.PlayerRepositoryFactory
import dev.ambon.persistence.WorldStatePersistenceWorker
import dev.ambon.persistence.WorldStateRepositoryFactory
import dev.ambon.redis.RedisConnectionManager
import dev.ambon.redis.redisObjectMapper
import dev.ambon.session.AtomicSessionIdFactory
import dev.ambon.sharding.EngineAddress
import dev.ambon.transport.BlockingSocketTransport
import dev.ambon.transport.KtorWebSocketTransport
import dev.ambon.transport.OutboundRouter
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import java.time.Clock
import java.util.concurrent.Executors

private val log = KotlinLogging.logger {}

class MudServer(
    private val config: AppConfig,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engineDispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "ambonMUD-engine") }.asCoroutineDispatcher()
    private val authDispatcher =
        Executors
            .newFixedThreadPool(config.login.authThreads) { r ->
                Thread(r, "ambon-auth").also { it.isDaemon = true }
            }.asCoroutineDispatcher()
    private val telnetDispatcher = Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher()
    private val sessionIdFactory = AtomicSessionIdFactory()

    private lateinit var outboundRouter: OutboundRouter
    private lateinit var telnetTransport: BlockingSocketTransport
    private lateinit var webTransport: KtorWebSocketTransport
    private var engineJob: Job? = null
    private var routerJob: Job? = null
    private val shutdownSignal = CompletableDeferred<Unit>()

    private val clock = Clock.systemUTC()

    private val prometheusRegistry: PrometheusMeterRegistry? =
        ServerInfrastructure.createPrometheusRegistry(config, "ambonmud")

    private val gameMetrics: GameMetrics =
        ServerInfrastructure.createGameMetrics(prometheusRegistry)

    private val databaseManager =
        ServerInfrastructure.createDatabaseManager(config)

    private val redisManager: RedisConnectionManager? =
        ServerInfrastructure.createRedisManager(config)

    private val playerRepoChain =
        PlayerRepositoryFactory.buildChain(
            persistence = config.persistence,
            redis = config.redis,
            redisManager = redisManager,
            database = databaseManager?.database,
            metrics = gameMetrics,
        )

    private val coalescingRepo = playerRepoChain.coalescingRepository
    private val playerRepo = playerRepoChain.repository

    private val worldStateRepo =
        WorldStateRepositoryFactory.create(
            persistence = config.persistence,
            database = databaseManager?.database,
        )

    private var worldStatePersistenceWorker: WorldStatePersistenceWorker? = null

    private val guildRepo =
        GuildRepositoryFactory.create(
            persistence = config.persistence,
            database = databaseManager?.database,
        )

    private val instanceId: String =
        ServerInfrastructure.generateInstanceId(config)

    private val inbound: InboundBus =
        if (config.redis.enabled && config.redis.bus.enabled && redisManager != null) {
            RedisInboundBus(
                delegate = LocalInboundBus(capacity = config.server.inboundChannelCapacity),
                publisher = redisBusPublisher(redisManager),
                subscriberSetup = redisBusSubscriberSetup(redisManager),
                channelName = config.redis.bus.inboundChannel,
                instanceId = instanceId,
                mapper = redisObjectMapper,
                sharedSecret = config.redis.bus.sharedSecret,
            )
        } else {
            LocalInboundBus(capacity = config.server.inboundChannelCapacity)
        }

    private val outbound: OutboundBus =
        if (config.redis.enabled && config.redis.bus.enabled && redisManager != null) {
            RedisOutboundBus(
                delegate = LocalOutboundBus(capacity = config.server.outboundChannelCapacity),
                publisher = redisBusPublisher(redisManager),
                subscriberSetup = redisBusSubscriberSetup(redisManager),
                channelName = config.redis.bus.outboundChannel,
                instanceId = instanceId,
                mapper = redisObjectMapper,
                sharedSecret = config.redis.bus.sharedSecret,
            )
        } else {
            LocalOutboundBus(capacity = config.server.outboundChannelCapacity)
        }

    private var persistenceWorker: PersistenceWorker? = null

    private val items = ItemRegistry()
    private val mobs = MobRegistry()
    private val classRegistry =
        PlayerClassRegistry().also { reg ->
            PlayerClassRegistryLoader.load(config.engine.classes, reg)
        }
    private val raceRegistry =
        RaceRegistry().also { reg ->
            RaceRegistryLoader.load(config.engine.races, reg)
        }
    private val statRegistry =
        StatRegistry().also { reg ->
            StatRegistryLoader.load(config.engine.stats, reg)
        }
    private val progression = PlayerProgression(config.progression, classRegistry)
    private val shardingEnabled = config.sharding.enabled
    private val engineId = config.sharding.engineId
    private val configuredShardedZones = ServerInfrastructure.resolveConfiguredZones(config)
    private val zoneFilter = ServerInfrastructure.resolveZoneFilter(config, configuredShardedZones)

    private val world =
        WorldFactory.demoWorld(
            resources = config.world.resources,
            tiers = config.engine.mob.tiers,
            zoneFilter = zoneFilter,
            startRoom = config.world.startRoom?.let { RoomId(it) },
        )
    private val worldState = WorldStateRegistry(world)
    private val tickMillis: Long = config.server.tickMillis
    private val scheduler: Scheduler = Scheduler(clock)
    private val localZones = ServerInfrastructure.resolveLocalZones(config, configuredShardedZones, world)
    private val advertisedEngineAddress: EngineAddress =
        ServerInfrastructure.resolveEngineAddress(config, config.server.telnetPort)

    private val players =
        createPlayerRegistry(
            startRoom = world.startRoom,
            engineConfig = config.engine,
            repo = playerRepo,
            items = items,
            clock = clock,
            progression = progression,
            hashingContext = authDispatcher,
            classRegistry = classRegistry,
            raceRegistry = raceRegistry,
        )

    // --- Sharding infrastructure (null when sharding is disabled) ---
    private val zoneRegistry =
        ServerInfrastructure.createZoneRegistry(config, redisManager, engineId, advertisedEngineAddress, localZones)

    private val interEngineBus =
        ServerInfrastructure.createInterEngineBus(config, redisManager)

    private val instanceSelector =
        ServerInfrastructure.createInstanceSelector(config, zoneRegistry)

    private val playerLocationIndex =
        ServerInfrastructure.createPlayerLocationIndex(config, redisManager)

    private val handoffManager =
        ServerInfrastructure.createHandoffManager(
            config = config,
            players = players,
            items = items,
            outbound = outbound,
            interEngineBus = interEngineBus,
            zoneRegistry = zoneRegistry,
            world = world,
            clock = clock,
            instanceSelector = instanceSelector,
        )

    private val adminServer: AdminHttpServer? =
        if (config.admin.enabled) {
            AdminHttpServer(
                config = config.admin,
                players = players,
                playerRepo = playerRepo,
                mobs = mobs,
                world = world,
                metricsUrl =
                    if (config.observability.metricsEnabled) {
                        "http://localhost:${config.server.webPort}${config.observability.metricsEndpoint}"
                    } else {
                        ""
                    },
            )
        } else {
            null
        }

    private var shardingJobs = ServerInfrastructure.ShardingJobs()

    init {
        bindQueueMetrics()
        gameMetrics.bindSchedulerPendingActions(scheduler::size)
        gameMetrics.bindSchedulerOverdueActions(scheduler::overdueSize)
        coalescingRepo?.let { gameMetrics.bindWriteCoalescerDirtyCount(it::dirtyCount) }
    }

    private fun bindQueueMetrics() =
        ServerInfrastructure.bindQueueMetrics(inbound, outbound, gameMetrics)

    suspend fun start() {
        if (config.redis.enabled && config.redis.bus.enabled) {
            log.warn {
                "Redis bus mode enabled. Ensure ambonMUD.redis.bus.sharedSecret is rotated and managed securely."
            }
        }
        redisManager?.connect()
        (inbound as? RedisInboundBus)?.startSubscribing()
        (outbound as? RedisOutboundBus)?.startSubscribing()
        bindWorldStateGauges()

        if (coalescingRepo != null) {
            val worker =
                PersistenceWorker(
                    repo = coalescingRepo,
                    flushIntervalMs = config.persistence.worker.flushIntervalMs,
                    scope = scope,
                )
            worker.start()
            persistenceWorker = worker
            log.info { "Persistence worker started (flushIntervalMs=${config.persistence.worker.flushIntervalMs})" }
        }

        if (config.persistence.worker.enabled) {
            val wsWorker =
                WorldStatePersistenceWorker(
                    registry = worldState,
                    repo = worldStateRepo,
                    flushIntervalMs = config.persistence.worker.flushIntervalMs,
                    scope = scope,
                    engineDispatcher = engineDispatcher,
                )
            wsWorker.start()
            worldStatePersistenceWorker = wsWorker
            log.info { "World state persistence worker started" }
        }

        outboundRouter = OutboundRouter(outbound, scope, metrics = gameMetrics)
        routerJob = outboundRouter.start()

        shardingJobs = ServerInfrastructure.startShardingJobs(
            config = config,
            scope = scope,
            zoneRegistry = zoneRegistry,
            playerLocationIndex = playerLocationIndex,
            interEngineBus = interEngineBus,
            redisManager = redisManager,
            players = players,
            engineAddress = advertisedEngineAddress,
            localZones = localZones,
            world = world,
            clock = clock,
        )

        engineJob =
            scope.launch(engineDispatcher) {
                GameEngine(
                    inbound = inbound,
                    outbound = outbound,
                    players = players,
                    world = world,
                    mobs = mobs,
                    items = items,
                    clock = clock,
                    tickMillis = tickMillis,
                    scheduler = scheduler,
                    maxInboundEventsPerTick = config.server.maxInboundEventsPerTick,
                    inboundBudgetMs = config.server.inboundBudgetMs,
                    loginConfig = config.login,
                    engineConfig = config.engine,
                    progression = progression,
                    metrics = gameMetrics,
                    onShutdown = { shutdownSignal.complete(Unit) },
                    worldState = worldState,
                    sharding = ServerInfrastructure.buildShardingContext(
                        config = config,
                        handoffManager = handoffManager,
                        interEngineBus = interEngineBus,
                        zoneRegistry = zoneRegistry,
                        playerLocationIndex = playerLocationIndex,
                    ),
                    persistence = PersistenceContext(
                        worldStateRepository = worldStateRepo,
                        guildRepo = guildRepo,
                        playerRepo = playerRepo,
                    ),
                    classRegistryOverride = classRegistry,
                    raceRegistryOverride = raceRegistry,
                    statRegistryOverride = statRegistry,
                ).run()
            }

        telnetTransport = ServerInfrastructure.createTelnetTransport(
            config = config,
            inbound = inbound,
            outboundRouter = outboundRouter,
            sessionIdFactory = sessionIdFactory::allocate,
            scope = scope,
            metrics = gameMetrics,
            sessionDispatcher = telnetDispatcher,
        )
        telnetTransport.start()
        log.info { "Telnet transport bound on port ${config.server.telnetPort}" }

        webTransport = ServerInfrastructure.createWebTransport(
            config = config,
            inbound = inbound,
            outboundRouter = outboundRouter,
            sessionIdFactory = sessionIdFactory::allocate,
            prometheusRegistry = prometheusRegistry,
            metrics = gameMetrics,
        )
        webTransport.start()
        log.info { "WebSocket transport bound on ${config.transport.websocket.host}:${config.server.webPort}" }
        if (config.observability.metricsEnabled) {
            log.info { "Metrics enabled at ${config.observability.metricsEndpoint}" }
        }
        adminServer?.start()
    }

    private fun bindWorldStateGauges() {
        gameMetrics.bindPlayerRegistry { players.allPlayers().size }
        gameMetrics.bindMobRegistry { mobs.all().size }
        gameMetrics.bindRoomsOccupied {
            players
                .allPlayers()
                .map { it.roomId }
                .toSet()
                .size
        }
    }

    suspend fun awaitShutdown() = shutdownSignal.await()

    suspend fun stop() {
        runCatching { adminServer?.stop() }
        runCatching { telnetTransport.stop() }
        runCatching { webTransport.stop() }
        shardingJobs.stopAll()
        runCatching { engineJob?.cancelAndJoin() }
        runCatching { interEngineBus?.close() }
        runCatching { persistenceWorker?.shutdown() }
        runCatching { worldStatePersistenceWorker?.shutdown() }
        runCatching { redisManager?.close() }
        runCatching { databaseManager?.close() }
        scope.cancel()
        engineDispatcher.close()
        authDispatcher.close()
        telnetDispatcher.close()
        inbound.close()
        outbound.close()
        log.info { "Server stopped" }
    }
}
