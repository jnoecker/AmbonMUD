package dev.ambon.grpc

import dev.ambon.bus.BusPublisher
import dev.ambon.bus.BusSubscriberSetup
import dev.ambon.bus.InboundBus
import dev.ambon.bus.LocalInboundBus
import dev.ambon.bus.LocalOutboundBus
import dev.ambon.bus.OutboundBus
import dev.ambon.config.AppConfig
import dev.ambon.domain.world.WorldFactory
import dev.ambon.engine.GameEngine
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.PlayerProgression
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.engine.scheduler.Scheduler
import dev.ambon.metrics.GameMetrics
import dev.ambon.metrics.MetricsHttpServer
import dev.ambon.persistence.PersistenceWorker
import dev.ambon.persistence.PlayerRepository
import dev.ambon.persistence.RedisCachingPlayerRepository
import dev.ambon.persistence.WriteCoalescingPlayerRepository
import dev.ambon.persistence.YamlPlayerRepository
import dev.ambon.redis.RedisConnectionManager
import dev.ambon.redis.redisObjectMapper
import dev.ambon.sharding.EngineAddress
import dev.ambon.sharding.HandoffManager
import dev.ambon.sharding.InterEngineBus
import dev.ambon.sharding.LocalInterEngineBus
import dev.ambon.sharding.RedisInterEngineBus
import dev.ambon.sharding.StaticZoneRegistry
import dev.ambon.sharding.ZoneRegistry
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

    private val yamlRepo =
        YamlPlayerRepository(
            rootDir = Paths.get(config.persistence.rootDir),
            metrics = gameMetrics,
        )

    private val redisManager: RedisConnectionManager? =
        if (config.redis.enabled) RedisConnectionManager(config.redis) else null

    private val redisRepo: RedisCachingPlayerRepository? =
        if (redisManager != null) {
            RedisCachingPlayerRepository(
                delegate = yamlRepo,
                cache = redisManager,
                cacheTtlSeconds = config.redis.cacheTtlSeconds,
            )
        } else {
            null
        }

    private val l2Repo: PlayerRepository = redisRepo ?: yamlRepo

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

    private val world =
        WorldFactory.demoWorld(
            resources = config.world.resources,
            tiers = config.engine.mob.tiers,
            zoneFilter = if (config.sharding.enabled) config.sharding.zones.toSet() else emptySet(),
        )
    private val scheduler: Scheduler = Scheduler(clock)

    private val players =
        PlayerRegistry(
            startRoom = world.startRoom,
            repo = playerRepo,
            items = items,
            clock = clock,
            progression = progression,
        )

    // --- Sharding infrastructure (null when sharding is disabled) ---
    private val shardingEnabled = config.sharding.enabled
    private val engineId = config.sharding.engineId

    private val zoneRegistry: ZoneRegistry? =
        if (shardingEnabled) {
            StaticZoneRegistry(
                mapOf(
                    engineId to Pair(
                        EngineAddress(engineId, "localhost", config.grpc.server.port),
                        config.sharding.zones.toSet(),
                    ),
                ),
            )
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

    private val handoffManager: HandoffManager? =
        if (shardingEnabled && interEngineBus != null && zoneRegistry != null) {
            HandoffManager(
                engineId = engineId,
                players = players,
                items = items,
                outbound = outbound,
                bus = interEngineBus,
                zoneRegistry = zoneRegistry,
                clock = clock,
            )
        } else {
            null
        }

    private var persistenceWorker: PersistenceWorker? = null
    private var outboundRouter: OutboundRouter? = null
    private var engineJob: Job? = null
    private var metricsHttpServer: MetricsHttpServer? = null

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
        if (zoneRegistry != null) {
            val addr = EngineAddress(engineId, "localhost", config.grpc.server.port)
            zoneRegistry.claimZones(engineId, addr, config.sharding.zones.toSet())
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
                    loginConfig = config.login,
                    engineConfig = config.engine,
                    progression = progression,
                    metrics = gameMetrics,
                    onShutdown = { shutdownSignal.complete(Unit) },
                    handoffManager = handoffManager,
                    interEngineBus = interEngineBus,
                    engineId = engineId,
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
        runCatching { engineJob?.cancelAndJoin() }
        runCatching { interEngineBus?.close() }
        runCatching { persistenceWorker?.shutdown() }
        runCatching { redisManager?.close() }
        scope.cancel()
        engineDispatcher.close()
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
