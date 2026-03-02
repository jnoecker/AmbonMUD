package dev.ambon.grpc

import dev.ambon.bus.BusPublisher
import dev.ambon.bus.BusSubscriberSetup
import dev.ambon.bus.InboundBus
import dev.ambon.bus.LocalInboundBus
import dev.ambon.bus.LocalOutboundBus
import dev.ambon.bus.OutboundBus
import dev.ambon.config.AppConfig
import dev.ambon.config.PersistenceBackend
import dev.ambon.config.ShardingRegistryType
import dev.ambon.domain.world.WorldFactory
import dev.ambon.engine.GameEngine
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.PlayerProgression
import dev.ambon.engine.ShardingContext
import dev.ambon.engine.createPlayerRegistry
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.engine.scheduler.Scheduler
import dev.ambon.metrics.GameMetrics
import dev.ambon.metrics.MetricsHttpServer
import dev.ambon.persistence.DatabaseManager
import dev.ambon.persistence.PersistenceWorker
import dev.ambon.persistence.PlayerRepository
import dev.ambon.persistence.PostgresPlayerRepository
import dev.ambon.persistence.RedisCachingPlayerRepository
import dev.ambon.persistence.WriteCoalescingPlayerRepository
import dev.ambon.persistence.YamlPlayerRepository
import dev.ambon.redis.RedisConnectionManager
import dev.ambon.redis.redisObjectMapper
import dev.ambon.sharding.EngineAddress
import dev.ambon.sharding.HandoffManager
import dev.ambon.sharding.InstanceSelector
import dev.ambon.sharding.InterEngineBus
import dev.ambon.sharding.LoadBalancedInstanceSelector
import dev.ambon.sharding.LocalInterEngineBus
import dev.ambon.sharding.LoggingScaleDecisionPublisher
import dev.ambon.sharding.PlayerLocationIndex
import dev.ambon.sharding.RedisInterEngineBus
import dev.ambon.sharding.RedisPlayerLocationIndex
import dev.ambon.sharding.RedisScaleDecisionPublisher
import dev.ambon.sharding.RedisZoneRegistry
import dev.ambon.sharding.ScaleDecisionPublisher
import dev.ambon.sharding.StaticZoneRegistry
import dev.ambon.sharding.ThresholdInstanceScaler
import dev.ambon.sharding.ZoneRegistry
import io.github.oshai.kotlinlogging.KotlinLogging
import io.lettuce.core.pubsub.RedisPubSubAdapter
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.file.Paths
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
        if (config.observability.metricsEnabled) {
            PrometheusMeterRegistry(PrometheusConfig.DEFAULT).also { reg ->
                reg.config().commonTags("service", "ambonmud-engine")
                config.observability.staticTags.forEach { (k, v) -> reg.config().commonTags(k, v) }
            }
        } else {
            null
        }

    private val gameMetrics: GameMetrics =
        if (prometheusRegistry != null) GameMetrics(prometheusRegistry) else GameMetrics.noop()

    private val databaseManager: DatabaseManager? =
        if (config.persistence.backend == PersistenceBackend.POSTGRES) {
            DatabaseManager(config.database).also { it.migrate() }
        } else {
            null
        }

    private val baseRepo: PlayerRepository =
        when (config.persistence.backend) {
            PersistenceBackend.YAML ->
                YamlPlayerRepository(
                    rootDir = Paths.get(config.persistence.rootDir),
                    metrics = gameMetrics,
                )
            PersistenceBackend.POSTGRES ->
                PostgresPlayerRepository(
                    database = databaseManager!!.database,
                    metrics = gameMetrics,
                )
        }

    private val redisManager: RedisConnectionManager? =
        if (config.redis.enabled) RedisConnectionManager(config.redis) else null

    private val redisRepo: RedisCachingPlayerRepository? =
        if (redisManager != null) {
            RedisCachingPlayerRepository(
                delegate = baseRepo,
                cache = redisManager,
                cacheTtlSeconds = config.redis.cacheTtlSeconds,
            )
        } else {
            null
        }

    private val l2Repo: PlayerRepository = redisRepo ?: baseRepo

    private val coalescingRepo: WriteCoalescingPlayerRepository? =
        if (config.persistence.worker.enabled) WriteCoalescingPlayerRepository(l2Repo) else null

    private val playerRepo = coalescingRepo ?: l2Repo

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
    private val configuredShardedZones =
        config.sharding.zones
            .map(String::trim)
            .filter { it.isNotEmpty() }
            .toSet()
    private val zoneFilter =
        if (shardingEnabled && configuredShardedZones.isNotEmpty()) {
            configuredShardedZones
        } else {
            emptySet()
        }

    private val world =
        WorldFactory.demoWorld(
            resources = config.world.resources,
            tiers = config.engine.mob.tiers,
            zoneFilter = zoneFilter,
        )
    private val scheduler: Scheduler = Scheduler(clock)
    private val localZones: Set<String> =
        if (shardingEnabled && configuredShardedZones.isNotEmpty()) {
            configuredShardedZones
        } else {
            world.rooms.keys.mapTo(linkedSetOf()) { it.zone }
        }
    private val advertisedEngineAddress: EngineAddress =
        EngineAddress(
            engineId = engineId,
            host = config.sharding.advertiseHost,
            port = config.sharding.advertisePort ?: config.grpc.server.port,
        )

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
    private val zoneRegistry: ZoneRegistry? =
        if (shardingEnabled) {
            when (config.sharding.registry.type) {
                ShardingRegistryType.REDIS -> {
                    val manager =
                        requireNotNull(redisManager) {
                            "Sharding registry type REDIS requires ambonMUD.redis.enabled=true"
                        }
                    RedisZoneRegistry(
                        redis = manager,
                        mapper = redisObjectMapper,
                        leaseTtlSeconds = config.sharding.registry.leaseTtlSeconds,
                        instancing = config.sharding.instancing.enabled,
                        defaultCapacity = config.sharding.instancing.defaultCapacity,
                    )
                }

                ShardingRegistryType.STATIC -> {
                    val configuredAssignments = config.sharding.registry.assignments
                    val assignmentMap =
                        if (configuredAssignments.isEmpty()) {
                            mapOf(engineId to Pair(advertisedEngineAddress, localZones))
                        } else {
                            configuredAssignments.associate { assignment ->
                                assignment.engineId to
                                    Pair(
                                        EngineAddress(assignment.engineId, assignment.host, assignment.port),
                                        assignment.zones
                                            .map(String::trim)
                                            .filter { it.isNotEmpty() }
                                            .toSet(),
                                    )
                            }
                        }
                    StaticZoneRegistry(
                        assignments = assignmentMap,
                        instancing = config.sharding.instancing.enabled,
                    )
                }
            }
        } else {
            null
        }

    private val interEngineBus: InterEngineBus? =
        if (shardingEnabled && redisManager != null) {
            RedisInterEngineBus(
                engineId = engineId,
                publisher = makeBusPublisher(redisManager),
                subscriberSetup = makeBusSubscriberSetup(redisManager),
                mapper = redisObjectMapper,
            )
        } else if (shardingEnabled) {
            LocalInterEngineBus()
        } else {
            null
        }

    private val instanceSelector: InstanceSelector? =
        if (shardingEnabled && config.sharding.instancing.enabled && zoneRegistry != null) {
            LoadBalancedInstanceSelector(zoneRegistry)
        } else {
            null
        }

    private val playerLocationIndex: PlayerLocationIndex? =
        if (shardingEnabled && config.sharding.playerIndex.enabled && redisManager != null) {
            val keyTtlSeconds = (config.sharding.playerIndex.heartbeatMs * 3L / 1_000L).coerceAtLeast(1L)
            RedisPlayerLocationIndex(
                engineId = engineId,
                redis = redisManager,
                keyTtlSeconds = keyTtlSeconds,
            )
        } else {
            null
        }

    private val handoffManager: HandoffManager? =
        if (shardingEnabled && interEngineBus != null && zoneRegistry != null) {
            HandoffManager(
                engineId = engineId,
                players = players,
                items = items,
                outbound = outbound,
                bus = interEngineBus,
                zoneRegistry = zoneRegistry,
                isTargetRoomLocal = world.rooms::containsKey,
                clock = clock,
                ackTimeoutMs = config.sharding.handoff.ackTimeoutMs,
                instanceSelector = instanceSelector,
            )
        } else {
            null
        }

    private var persistenceWorker: PersistenceWorker? = null
    private var engineJob: Job? = null
    private var metricsHttpServer: MetricsHttpServer? = null
    private var zoneHeartbeatJob: Job? = null
    private var playerIndexHeartbeatJob: Job? = null
    private var zoneLoadReportJob: Job? = null
    private var autoScaleJob: Job? = null

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

        // OutboundRouter isn't used in engine mode (GrpcOutboundDispatcher does the routing),
        // but we still need to prevent the outbound bus channel from blocking.
        // The GrpcOutboundDispatcher in EngineGrpcServer drains it.

        // Start inter-engine bus and register zones
        if (interEngineBus != null) {
            interEngineBus.start()
        }

        // Start player-location index heartbeat
        if (playerLocationIndex != null) {
            val heartbeatMs = config.sharding.playerIndex.heartbeatMs
            playerIndexHeartbeatJob =
                scope.launch {
                    while (isActive) {
                        delay(heartbeatMs)
                        runCatching {
                            playerLocationIndex.refreshTtls()
                        }.onFailure { err ->
                            log.warn(err) { "Player location index heartbeat failed" }
                        }
                    }
                }
        }
        if (zoneRegistry != null) {
            zoneRegistry.claimZones(engineId, advertisedEngineAddress, localZones)
            val heartbeatIntervalMs =
                ((config.sharding.registry.leaseTtlSeconds * 1_000L) / 3L)
                    .coerceAtLeast(1_000L)
            zoneHeartbeatJob =
                scope.launch {
                    while (isActive) {
                        delay(heartbeatIntervalMs)
                        runCatching {
                            zoneRegistry.renewLease(engineId)
                            zoneRegistry.claimZones(engineId, advertisedEngineAddress, localZones)
                        }.onFailure { err ->
                            log.warn(err) { "Zone lease heartbeat failed for engine=$engineId" }
                        }
                    }
                }
            if (config.sharding.instancing.enabled) {
                val loadReportIntervalMs = config.sharding.instancing.loadReportIntervalMs
                zoneLoadReportJob =
                    scope.launch {
                        while (isActive) {
                            delay(loadReportIntervalMs)
                            runCatching {
                                val zoneCounts =
                                    players
                                        .allPlayers()
                                        .groupBy { it.roomId.zone }
                                        .mapValues { (_, ps) -> ps.size }
                                zoneRegistry.reportLoad(engineId, zoneCounts)
                            }.onFailure { err ->
                                log.warn(err) { "Zone load report failed for engine=$engineId" }
                            }
                        }
                    }
            }
            // Auto-scaling signal evaluation
            if (config.sharding.instancing.autoScale.enabled) {
                val scaler =
                    ThresholdInstanceScaler(
                        registry = zoneRegistry,
                        scaleUpThreshold = config.sharding.instancing.autoScale.scaleUpThreshold,
                        scaleDownThreshold = config.sharding.instancing.autoScale.scaleDownThreshold,
                        cooldownMs = config.sharding.instancing.autoScale.cooldownMs,
                        minInstances =
                            if (world.startRoom.value.contains(":")) {
                                val startZone = world.startRoom.value.substringBefore(":")
                                mapOf(startZone to config.sharding.instancing.startZoneMinInstances)
                            } else {
                                emptyMap()
                            },
                        clock = clock,
                    )
                val publisher: ScaleDecisionPublisher =
                    if (redisManager?.commands != null) {
                        RedisScaleDecisionPublisher(redisManager.commands!!)
                    } else {
                        LoggingScaleDecisionPublisher()
                    }
                val evalIntervalMs = config.sharding.instancing.autoScale.evaluationIntervalMs
                autoScaleJob =
                    scope.launch {
                        while (isActive) {
                            delay(evalIntervalMs)
                            runCatching {
                                val decisions = scaler.evaluate()
                                if (decisions.isNotEmpty()) {
                                    publisher.publish(decisions)
                                }
                            }.onFailure { err ->
                                log.warn(err) { "Auto-scale evaluation failed" }
                            }
                        }
                    }
            }
        }

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
                    sharding = ShardingContext(
                        engineId = engineId,
                        handoffManager = handoffManager,
                        interEngineBus = interEngineBus,
                        peerEngineCount = {
                            zoneRegistry
                                ?.allAssignments()
                                ?.values
                                ?.map { it.engineId }
                                ?.filter { it != engineId }
                                ?.toSet()
                                ?.size
                                ?: 0
                        },
                        playerLocationIndex = playerLocationIndex,
                        zoneRegistry = zoneRegistry,
                    ),
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
        runCatching { zoneHeartbeatJob?.cancelAndJoin() }
        runCatching { playerIndexHeartbeatJob?.cancelAndJoin() }
        runCatching { zoneLoadReportJob?.cancelAndJoin() }
        runCatching { autoScaleJob?.cancelAndJoin() }
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

    private fun makeBusPublisher(manager: RedisConnectionManager): BusPublisher =
        BusPublisher { ch, msg ->
            manager.asyncCommands?.publish(ch, msg)
        }

    private fun makeBusSubscriberSetup(manager: RedisConnectionManager): BusSubscriberSetup =
        BusSubscriberSetup { ch, onMessage ->
            val conn = manager.connectPubSub()
            if (conn != null) {
                conn.addListener(
                    object : RedisPubSubAdapter<String, String>() {
                        override fun message(
                            channel: String,
                            message: String,
                        ) = onMessage(message)
                    },
                )
                conn.sync().subscribe(ch)
            }
        }
}
