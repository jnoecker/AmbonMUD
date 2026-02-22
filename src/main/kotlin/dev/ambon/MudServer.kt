package dev.ambon

import dev.ambon.bus.BusPublisher
import dev.ambon.bus.BusSubscriberSetup
import dev.ambon.bus.InboundBus
import dev.ambon.bus.LocalInboundBus
import dev.ambon.bus.LocalOutboundBus
import dev.ambon.bus.OutboundBus
import dev.ambon.bus.RedisInboundBus
import dev.ambon.bus.RedisOutboundBus
import dev.ambon.config.AppConfig
import dev.ambon.domain.world.WorldFactory
import dev.ambon.engine.GameEngine
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.PlayerProgression
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.engine.scheduler.Scheduler
import dev.ambon.metrics.GameMetrics
import dev.ambon.persistence.PersistenceWorker
import dev.ambon.persistence.PlayerRepository
import dev.ambon.persistence.RedisCachingPlayerRepository
import dev.ambon.persistence.WriteCoalescingPlayerRepository
import dev.ambon.persistence.YamlPlayerRepository
import dev.ambon.redis.RedisConnectionManager
import dev.ambon.redis.redisObjectMapper
import dev.ambon.session.AtomicSessionIdFactory
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

    private val instanceId: String =
        config.redis.bus.instanceId.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()

    private val inbound: InboundBus =
        if (config.redis.enabled && config.redis.bus.enabled && redisManager != null) {
            RedisInboundBus(
                delegate = LocalInboundBus(capacity = config.server.inboundChannelCapacity),
                publisher = makeBusPublisher(redisManager),
                subscriberSetup = makeBusSubscriberSetup(redisManager),
                channelName = config.redis.bus.inboundChannel,
                instanceId = instanceId,
                mapper = redisObjectMapper,
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
            )
        } else {
            LocalOutboundBus(capacity = config.server.outboundChannelCapacity)
        }

    private var persistenceWorker: PersistenceWorker? = null

    private val items = ItemRegistry()
    private val mobs = MobRegistry()
    private val progression = PlayerProgression(config.progression)

    private val world = WorldFactory.demoWorld(config.world.resources, config.engine.mob.tiers)
    private val tickMillis: Long = config.server.tickMillis
    private val scheduler: Scheduler = Scheduler(clock)

    private val players =
        PlayerRegistry(
            startRoom = world.startRoom,
            repo = playerRepo,
            items = items,
            clock = clock,
            progression = progression,
        )

    suspend fun start() {
        if (config.redis.enabled && config.redis.bus.enabled) {
            log.warn {
                "Redis bus mode is experimental and may publish sensitive inbound input. " +
                    "Use only in development until Phase 4 hardening."
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

        outboundRouter = OutboundRouter(outbound, scope, metrics = gameMetrics)
        routerJob = outboundRouter.start()

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
        gameMetrics.bindRoomsOccupied { players.allPlayers().map { it.roomId }.toSet().size }
    }

    suspend fun awaitShutdown() = shutdownSignal.await()

    suspend fun stop() {
        runCatching { telnetTransport.stop() }
        runCatching { webTransport.stop() }
        runCatching { engineJob?.cancelAndJoin() }
        runCatching { persistenceWorker?.shutdown() }
        runCatching { redisManager?.close() }
        scope.cancel()
        engineDispatcher.close()
        inbound.close()
        outbound.close()
        log.info { "Server stopped" }
    }
}
