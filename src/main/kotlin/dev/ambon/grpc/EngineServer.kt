package dev.ambon.grpc

import dev.ambon.ServerInfrastructure
import dev.ambon.bus.InboundBus
import dev.ambon.bus.LocalInboundBus
import dev.ambon.bus.LocalOutboundBus
import dev.ambon.bus.OutboundBus
import dev.ambon.config.AppConfig
import dev.ambon.domain.world.WorldFactory
import dev.ambon.engine.GameEngine
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.PlayerProgression
import dev.ambon.engine.createPlayerRegistry
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.engine.scheduler.Scheduler
import dev.ambon.metrics.GameMetrics
import dev.ambon.metrics.MetricsHttpServer
import dev.ambon.persistence.PersistenceWorker
import dev.ambon.persistence.PlayerRepositoryFactory
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

/**
 * Engine-mode composition root.
 *
 * Starts the game engine, persistence worker, and gRPC server.
 * No transports (telnet/WebSocket) are started — clients connect via gateways.
 */
class EngineServer(
    private val config: AppConfig,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engineDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "ambonMUD-engine") }.asCoroutineDispatcher()
    private val authDispatcher =
        Executors
            .newFixedThreadPool(config.login.authThreads) { r ->
                Thread(r, "ambon-auth").also { it.isDaemon = true }
            }.asCoroutineDispatcher()

    private val clock = Clock.systemUTC()
    private val shutdownSignal = CompletableDeferred<Unit>()

    private val prometheusRegistry: PrometheusMeterRegistry? =
        ServerInfrastructure.createPrometheusRegistry(config, "ambonmud-engine")

    private val gameMetrics: GameMetrics =
        ServerInfrastructure.createGameMetrics(prometheusRegistry)

    private val databaseManager =
        ServerInfrastructure.createDatabaseManager(config)

    private val redisManager =
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

    private val inbound: InboundBus = LocalInboundBus(capacity = config.server.inboundChannelCapacity)
    private val outbound: OutboundBus = LocalOutboundBus(capacity = config.server.outboundChannelCapacity)

    private val grpcServer =
        EngineGrpcServer(
            port = config.grpc.server.port,
            inbound = inbound,
            outbound = outbound,
            scope = scope,
            metrics = gameMetrics,
        )

    private val items = ItemRegistry()
    private val mobs = MobRegistry()
    private val progression = PlayerProgression(config.progression)
    private val shardingEnabled = config.sharding.enabled
    private val engineId = config.sharding.engineId
    private val configuredShardedZones = ServerInfrastructure.resolveConfiguredZones(config)
    private val zoneFilter = ServerInfrastructure.resolveZoneFilter(config, configuredShardedZones)

    private val world =
        WorldFactory.demoWorld(
            resources = config.world.resources,
            tiers = config.engine.mob.tiers,
            zoneFilter = zoneFilter,
            imagesBaseUrl = config.images.baseUrl,
            videosBaseUrl = config.videos.baseUrl,
            audioBaseUrl = config.audio.baseUrl,
        )
    private val scheduler: Scheduler = Scheduler(clock)
    private val localZones = ServerInfrastructure.resolveLocalZones(config, configuredShardedZones, world)
    private val advertisedEngineAddress =
        ServerInfrastructure.resolveEngineAddress(config, config.grpc.server.port)

    private val players =
        createPlayerRegistry(
            startRoom = world.startRoom,
            engineConfig = config.engine,
            repo = playerRepo,
            items = items,
            clock = clock,
            progression = progression,
            hashingContext = authDispatcher,
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

    private var persistenceWorker: PersistenceWorker? = null
    private var engineJob: Job? = null
    private var metricsHttpServer: MetricsHttpServer? = null
    private var shardingJobs = ServerInfrastructure.ShardingJobs()

    suspend fun start() {
        redisManager?.connect()

        if (coalescingRepo != null) {
            val worker =
                PersistenceWorker(
                    repo = coalescingRepo,
                    flushIntervalMs = config.persistence.worker.flushIntervalMs,
                    scope = scope,
                )
            worker.start()
            persistenceWorker = worker
        }

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
                    tickMillis = config.server.tickMillis,
                    scheduler = scheduler,
                    maxInboundEventsPerTick = config.server.maxInboundEventsPerTick,
                    inboundBudgetMs = config.server.inboundBudgetMs,
                    loginConfig = config.login,
                    engineConfig = config.engine,
                    progression = progression,
                    metrics = gameMetrics,
                    onShutdown = { shutdownSignal.complete(Unit) },
                    sharding = ServerInfrastructure.buildShardingContext(
                        config = config,
                        handoffManager = handoffManager,
                        interEngineBus = interEngineBus,
                        zoneRegistry = zoneRegistry,
                        playerLocationIndex = playerLocationIndex,
                    ),
                    imagesBaseUrl = config.images.baseUrl,
                ).run()
            }

        grpcServer.start()
        if (prometheusRegistry != null) {
            val server =
                MetricsHttpServer(
                    port = config.observability.metricsHttpPort,
                    registry = prometheusRegistry,
                    endpoint = config.observability.metricsEndpoint,
                )
            server.start()
            metricsHttpServer = server
        }
        log.info { "Engine server started (gRPC port=${config.grpc.server.port})" }
    }

    suspend fun awaitShutdown() = shutdownSignal.await()

    suspend fun stop() {
        runCatching { metricsHttpServer?.stop() }
        runCatching { grpcServer.stop() }
        shardingJobs.stopAll()
        runCatching { engineJob?.cancelAndJoin() }
        runCatching { interEngineBus?.close() }
        runCatching { persistenceWorker?.shutdown() }
        runCatching { redisManager?.close() }
        runCatching { databaseManager?.close() }
        scope.cancel()
        engineDispatcher.close()
        authDispatcher.close()
        inbound.close()
        outbound.close()
        log.info { "Engine server stopped" }
    }
}
