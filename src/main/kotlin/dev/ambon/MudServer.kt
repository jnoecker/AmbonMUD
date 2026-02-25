package dev.ambon

import dev.ambon.admin.AdminHttpServer
import dev.ambon.bus.BusPublisher
import dev.ambon.bus.BusSubscriberSetup
import dev.ambon.bus.InboundBus
import dev.ambon.bus.LocalInboundBus
import dev.ambon.bus.LocalOutboundBus
import dev.ambon.bus.OutboundBus
import dev.ambon.bus.RedisInboundBus
import dev.ambon.bus.RedisOutboundBus
import dev.ambon.config.AppConfig
import dev.ambon.config.PersistenceBackend
import dev.ambon.config.ShardingRegistryType
import dev.ambon.domain.world.WorldFactory
import dev.ambon.engine.GameEngine
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.PlayerProgression
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.WorldStateRegistry
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.engine.scheduler.Scheduler
import dev.ambon.metrics.GameMetrics
import dev.ambon.persistence.DatabaseManager
import dev.ambon.persistence.PersistenceWorker
import dev.ambon.persistence.PlayerRepository
import dev.ambon.persistence.PostgresPlayerRepository
import dev.ambon.persistence.PostgresWorldStateRepository
import dev.ambon.persistence.RedisCachingPlayerRepository
import dev.ambon.persistence.WorldStatePersistenceWorker
import dev.ambon.persistence.WorldStateRepository
import dev.ambon.persistence.WriteCoalescingPlayerRepository
import dev.ambon.persistence.YamlPlayerRepository
import dev.ambon.persistence.YamlWorldStateRepository
import dev.ambon.redis.RedisConnectionManager
import dev.ambon.redis.redisObjectMapper
import dev.ambon.session.AtomicSessionIdFactory
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
import dev.ambon.transport.BlockingSocketTransport
import dev.ambon.transport.KtorWebSocketTransport
import dev.ambon.transport.OutboundRouter
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
import kotlinx.coroutines.launch
import java.nio.file.Paths
import java.time.Clock
import java.util.UUID
import java.util.concurrent.Executors

private val log = KotlinLogging.logger {}

class MudServer(
    private val config: AppConfig,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engineDispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "ambonMUD-engine") }.asCoroutineDispatcher()
    private val sessionIdFactory = AtomicSessionIdFactory()

    private lateinit var outboundRouter: OutboundRouter
    private lateinit var telnetTransport: BlockingSocketTransport
    private lateinit var webTransport: KtorWebSocketTransport
    private var engineJob: Job? = null
    private var routerJob: Job? = null
    private val shutdownSignal = CompletableDeferred<Unit>()

    private val clock = Clock.systemUTC()

    private val prometheusRegistry: PrometheusMeterRegistry? =
        if (config.observability.metricsEnabled) {
            PrometheusMeterRegistry(PrometheusConfig.DEFAULT).also { reg ->
                reg.config().commonTags("service", "ambonmud")
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

    private val worldStateRepo: WorldStateRepository =
        when (config.persistence.backend) {
            PersistenceBackend.YAML ->
                YamlWorldStateRepository(
                    rootDir = Paths.get(config.persistence.rootDir),
                )
            PersistenceBackend.POSTGRES ->
                PostgresWorldStateRepository(
                    database = databaseManager!!.database,
                )
        }

    private var worldStatePersistenceWorker: WorldStatePersistenceWorker? = null

    private val instanceId: String =
        config.redis.bus.instanceId
            .takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()

    private val inbound: InboundBus =
        if (config.redis.enabled && config.redis.bus.enabled && redisManager != null) {
            RedisInboundBus(
                delegate = LocalInboundBus(capacity = config.server.inboundChannelCapacity),
                publisher = makeBusPublisher(redisManager),
                subscriberSetup = makeBusSubscriberSetup(redisManager),
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
                publisher = makeBusPublisher(redisManager),
                subscriberSetup = makeBusSubscriberSetup(redisManager),
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
    private val worldState = WorldStateRegistry(world)
    private val tickMillis: Long = config.server.tickMillis
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
            port = config.sharding.advertisePort ?: config.server.telnetPort,
        )

    private val players =
        PlayerRegistry(
            startRoom = world.startRoom,
            repo = playerRepo,
            items = items,
            clock = clock,
            progression = progression,
            hashingContext = Dispatchers.IO,
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

    private var zoneHeartbeatJob: Job? = null
    private var playerIndexHeartbeatJob: Job? = null
    private var zoneLoadReportJob: Job? = null
    private var autoScaleJob: Job? = null

    init {
        bindQueueMetrics()
        gameMetrics.bindSchedulerPendingActions(scheduler::size)
        coalescingRepo?.let { gameMetrics.bindWriteCoalescerDirtyCount(it::dirtyCount) }
    }

    private fun bindQueueMetrics() {
        when (val bus = inbound) {
            is LocalInboundBus -> {
                gameMetrics.bindInboundBusQueue(bus::depth) { bus.capacity }
            }
            is RedisInboundBus -> {
                val delegate = bus.delegateForMetrics()
                gameMetrics.bindInboundBusQueue(delegate::depth) { delegate.capacity }
            }
        }

        when (val bus = outbound) {
            is LocalOutboundBus -> {
                gameMetrics.bindOutboundBusQueue(bus::depth) { bus.capacity }
            }
            is RedisOutboundBus -> {
                val delegate = bus.delegateForMetrics()
                gameMetrics.bindOutboundBusQueue(delegate::depth) { delegate.capacity }
            }
        }
    }

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

        // Start inter-engine bus and register zones
        if (interEngineBus != null) {
            interEngineBus.start()
        }

        // Start player-location index heartbeat
        if (playerLocationIndex != null) {
            val heartbeatMs = config.sharding.playerIndex.heartbeatMs
            // refreshTtls uses the index's own internal name tracking —
            // no access to PlayerRegistry needed, avoiding a cross-thread read.
            playerIndexHeartbeatJob =
                scope.launchPeriodic(heartbeatMs, "Player location index heartbeat") {
                    playerLocationIndex.refreshTtls()
                }
        }
        if (zoneRegistry != null) {
            zoneRegistry.claimZones(engineId, advertisedEngineAddress, localZones)
            val heartbeatIntervalMs =
                ((config.sharding.registry.leaseTtlSeconds * 1_000L) / 3L)
                    .coerceAtLeast(1_000L)
            zoneHeartbeatJob =
                scope.launchPeriodic(heartbeatIntervalMs, "Zone lease heartbeat (engine=$engineId)") {
                    zoneRegistry.renewLease(engineId)
                    zoneRegistry.claimZones(engineId, advertisedEngineAddress, localZones)
                }
            // Zone load reporting for instance-aware routing
            if (config.sharding.instancing.enabled) {
                val loadReportIntervalMs = config.sharding.instancing.loadReportIntervalMs
                zoneLoadReportJob =
                    scope.launchPeriodic(loadReportIntervalMs, "Zone load report (engine=$engineId)") {
                        val zoneCounts =
                            players
                                .allPlayers()
                                .groupBy { it.roomId.zone }
                                .mapValues { (_, ps) -> ps.size }
                        zoneRegistry.reportLoad(engineId, zoneCounts)
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
                    scope.launchPeriodic(evalIntervalMs, "Auto-scale evaluation") {
                        val decisions = scaler.evaluate()
                        if (decisions.isNotEmpty()) {
                            publisher.publish(decisions)
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
                    tickMillis = tickMillis,
                    scheduler = scheduler,
                    maxInboundEventsPerTick = config.server.maxInboundEventsPerTick,
                    loginConfig = config.login,
                    engineConfig = config.engine,
                    progression = progression,
                    metrics = gameMetrics,
                    onShutdown = { shutdownSignal.complete(Unit) },
                    handoffManager = handoffManager,
                    interEngineBus = interEngineBus,
                    engineId = engineId,
                    playerLocationIndex = playerLocationIndex,
                    zoneRegistry = zoneRegistry,
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
                    worldState = worldState,
                    worldStateRepository = worldStateRepo,
                ).run()
            }

        telnetTransport =
            BlockingSocketTransport(
                port = config.server.telnetPort,
                inbound = inbound,
                outboundRouter = outboundRouter,
                sessionIdFactory = sessionIdFactory::allocate,
                scope = scope,
                sessionOutboundQueueCapacity = config.server.sessionOutboundQueueCapacity,
                maxLineLen = config.transport.telnet.maxLineLen,
                maxNonPrintablePerLine = config.transport.telnet.maxNonPrintablePerLine,
                maxInboundBackpressureFailures = config.transport.maxInboundBackpressureFailures,
                metrics = gameMetrics,
            )
        telnetTransport.start()
        log.info { "Telnet transport bound on port ${config.server.telnetPort}" }

        webTransport =
            KtorWebSocketTransport(
                port = config.server.webPort,
                inbound = inbound,
                outboundRouter = outboundRouter,
                sessionIdFactory = sessionIdFactory::allocate,
                host = config.transport.websocket.host,
                sessionOutboundQueueCapacity = config.server.sessionOutboundQueueCapacity,
                stopGraceMillis = config.transport.websocket.stopGraceMillis,
                stopTimeoutMillis = config.transport.websocket.stopTimeoutMillis,
                maxLineLen = config.transport.telnet.maxLineLen,
                maxNonPrintablePerLine = config.transport.telnet.maxNonPrintablePerLine,
                maxInboundBackpressureFailures = config.transport.maxInboundBackpressureFailures,
                prometheusRegistry = prometheusRegistry,
                metricsEndpoint = config.observability.metricsEndpoint,
                metrics = gameMetrics,
            )
        webTransport.start()
        log.info { "WebSocket transport bound on ${config.transport.websocket.host}:${config.server.webPort}" }
        if (config.observability.metricsEnabled) {
            log.info { "Metrics enabled at ${config.observability.metricsEndpoint}" }
        }
        adminServer?.start()
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
        runCatching { zoneHeartbeatJob?.cancelAndJoin() }
        runCatching { zoneLoadReportJob?.cancelAndJoin() }
        runCatching { autoScaleJob?.cancelAndJoin() }
        runCatching { playerIndexHeartbeatJob?.cancelAndJoin() }
        runCatching { engineJob?.cancelAndJoin() }
        runCatching { interEngineBus?.close() }
        runCatching { persistenceWorker?.shutdown() }
        runCatching { worldStatePersistenceWorker?.shutdown() }
        runCatching { redisManager?.close() }
        runCatching { databaseManager?.close() }
        scope.cancel()
        engineDispatcher.close()
        inbound.close()
        outbound.close()
        log.info { "Server stopped" }
    }
}
