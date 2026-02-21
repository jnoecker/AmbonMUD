package dev.ambon

import dev.ambon.bus.LocalInboundBus
import dev.ambon.bus.LocalOutboundBus
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
import dev.ambon.session.AtomicSessionIdFactory
import dev.ambon.transport.BlockingSocketTransport
import dev.ambon.transport.KtorWebSocketTransport
import dev.ambon.transport.OutboundRouter
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.nio.file.Paths
import java.time.Clock
import java.util.concurrent.Executors

private val log = KotlinLogging.logger {}

class MudServer(
    private val config: AppConfig,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val inbound = LocalInboundBus(capacity = config.server.inboundChannelCapacity)
    private val outbound = LocalOutboundBus(capacity = config.server.outboundChannelCapacity)

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
        redisManager?.connect()
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

    private fun bindWorldStateGauges() {
        gameMetrics.bindPlayerRegistry { players.allPlayers().size }
        gameMetrics.bindMobRegistry { mobs.all().size }
        gameMetrics.bindRoomsOccupied { players.allPlayers().map { it.roomId }.toSet().size }
    }

    suspend fun awaitShutdown() = shutdownSignal.await()

    suspend fun stop() {
        runCatching { telnetTransport.stop() }
        runCatching { webTransport.stop() }
        runCatching { persistenceWorker?.shutdown() }
        runCatching { redisManager?.close() }
        scope.cancel()
        engineDispatcher.close()
        inbound.close()
        outbound.close()
        log.info { "Server stopped" }
    }
}
