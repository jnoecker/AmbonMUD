package dev.ambon

import dev.ambon.bus.DepthAware
import dev.ambon.bus.InboundBus
import dev.ambon.bus.OutboundBus
import dev.ambon.bus.redisBusPublisher
import dev.ambon.bus.redisBusSubscriberSetup
import dev.ambon.config.AppConfig
import dev.ambon.config.PersistenceBackend
import dev.ambon.config.ShardingRegistryType
import dev.ambon.domain.world.World
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.ShardingContext
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.metrics.GameMetrics
import dev.ambon.persistence.DatabaseManager
import dev.ambon.redis.RedisConnectionManager
import dev.ambon.redis.redisObjectMapper
import dev.ambon.sharding.ClassicRedisZoneRegistry
import dev.ambon.sharding.EngineAddress
import dev.ambon.sharding.HandoffManager
import dev.ambon.sharding.InstanceSelector
import dev.ambon.sharding.InstancedRedisZoneRegistry
import dev.ambon.sharding.InterEngineBus
import dev.ambon.sharding.LoadBalancedInstanceSelector
import dev.ambon.sharding.LocalInterEngineBus
import dev.ambon.sharding.LoggingScaleDecisionPublisher
import dev.ambon.sharding.PlayerLocationIndex
import dev.ambon.sharding.RedisInterEngineBus
import dev.ambon.sharding.RedisPlayerLocationIndex
import dev.ambon.sharding.RedisScaleDecisionPublisher
import dev.ambon.sharding.ScaleDecisionPublisher
import dev.ambon.sharding.StaticZoneRegistry
import dev.ambon.sharding.ThresholdInstanceScaler
import dev.ambon.sharding.ZoneRegistry
import dev.ambon.transport.BlockingSocketTransport
import dev.ambon.transport.KtorWebSocketTransport
import dev.ambon.transport.OutboundRouter
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import java.time.Clock
import java.util.UUID
import kotlin.coroutines.CoroutineContext

/**
 * Shared factory functions for infrastructure that [MudServer],
 * [dev.ambon.grpc.EngineServer], and [dev.ambon.gateway.GatewayServer]
 * construct identically.
 */
object ServerInfrastructure {
    fun createPrometheusRegistry(
        config: AppConfig,
        serviceName: String,
    ): PrometheusMeterRegistry? =
        if (config.observability.metricsEnabled) {
            PrometheusMeterRegistry(PrometheusConfig.DEFAULT).also { reg ->
                reg.config().commonTags("service", serviceName)
                config.observability.staticTags.forEach { (k, v) -> reg.config().commonTags(k, v) }
            }
        } else {
            null
        }

    fun createGameMetrics(registry: PrometheusMeterRegistry?): GameMetrics =
        if (registry != null) GameMetrics(registry) else GameMetrics.noop()

    fun generateInstanceId(config: AppConfig): String =
        config.redis.bus.instanceId
            .takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()

    fun createRedisManager(config: AppConfig): RedisConnectionManager? =
        if (config.redis.enabled) RedisConnectionManager(config.redis) else null

    fun createTelnetTransport(
        config: AppConfig,
        inbound: InboundBus,
        outboundRouter: OutboundRouter,
        sessionIdFactory: () -> dev.ambon.domain.ids.SessionId,
        scope: CoroutineScope,
        metrics: GameMetrics,
        sessionDispatcher: CoroutineContext,
    ): BlockingSocketTransport =
        BlockingSocketTransport(
            port = config.server.telnetPort,
            inbound = inbound,
            outboundRouter = outboundRouter,
            sessionIdFactory = sessionIdFactory,
            scope = scope,
            sessionOutboundQueueCapacity = config.server.sessionOutboundQueueCapacity,
            maxLineLen = config.transport.telnet.maxLineLen,
            maxNonPrintablePerLine = config.transport.telnet.maxNonPrintablePerLine,
            maxInboundBackpressureFailures = config.transport.maxInboundBackpressureFailures,
            socketBacklog = config.transport.telnet.socketBacklog,
            metrics = metrics,
            sessionDispatcher = sessionDispatcher,
        )

    fun createWebTransport(
        config: AppConfig,
        inbound: InboundBus,
        outboundRouter: OutboundRouter,
        sessionIdFactory: () -> dev.ambon.domain.ids.SessionId,
        prometheusRegistry: PrometheusMeterRegistry?,
        metrics: GameMetrics,
    ): KtorWebSocketTransport =
        KtorWebSocketTransport(
            port = config.server.webPort,
            inbound = inbound,
            outboundRouter = outboundRouter,
            sessionIdFactory = sessionIdFactory,
            host = config.transport.websocket.host,
            sessionOutboundQueueCapacity = config.server.sessionOutboundQueueCapacity,
            stopGraceMillis = config.transport.websocket.stopGraceMillis,
            stopTimeoutMillis = config.transport.websocket.stopTimeoutMillis,
            maxLineLen = config.transport.telnet.maxLineLen,
            maxNonPrintablePerLine = config.transport.telnet.maxNonPrintablePerLine,
            maxInboundBackpressureFailures = config.transport.maxInboundBackpressureFailures,
            prometheusRegistry = prometheusRegistry,
            metricsEndpoint = config.observability.metricsEndpoint,
            metrics = metrics,
        )

    fun bindQueueMetrics(
        inbound: InboundBus,
        outbound: OutboundBus,
        metrics: GameMetrics,
    ) {
        (inbound as? DepthAware)?.let { metrics.bindInboundBusQueue(it::depth) { it.capacity } }
        (outbound as? DepthAware)?.let { metrics.bindOutboundBusQueue(it::depth) { it.capacity } }
    }

    fun createDatabaseManager(config: AppConfig): DatabaseManager? =
        if (config.persistence.backend == PersistenceBackend.POSTGRES) {
            DatabaseManager(config.database).also { it.migrate() }
        } else {
            null
        }

    /**
     * Computes the set of zone names this engine owns, trimmed and filtered.
     */
    fun resolveConfiguredZones(config: AppConfig): Set<String> =
        config.sharding.zones
            .map(String::trim)
            .filter { it.isNotEmpty() }
            .toSet()

    /**
     * Computes the zone filter to pass to [dev.ambon.domain.world.WorldFactory].
     * Returns the configured zone set when sharding is enabled; empty otherwise.
     */
    fun resolveZoneFilter(config: AppConfig, configuredZones: Set<String>): Set<String> =
        if (config.sharding.enabled && configuredZones.isNotEmpty()) configuredZones else emptySet()

    /**
     * Resolves which zones this engine owns at runtime.
     * When sharding is enabled with explicit zones, uses the configured set;
     * otherwise falls back to all zones present in the loaded world.
     */
    fun resolveLocalZones(config: AppConfig, configuredZones: Set<String>, world: World): Set<String> =
        if (config.sharding.enabled && configuredZones.isNotEmpty()) {
            configuredZones
        } else {
            world.rooms.keys.mapTo(linkedSetOf()) { it.zone }
        }

    /**
     * Builds the [EngineAddress] this engine advertises in zone ownership records.
     * [defaultPort] is the mode-specific port fallback (telnet for STANDALONE, gRPC for ENGINE).
     */
    fun resolveEngineAddress(config: AppConfig, defaultPort: Int): EngineAddress =
        EngineAddress(
            engineId = config.sharding.engineId,
            host = config.sharding.advertiseHost,
            port = config.sharding.advertisePort ?: defaultPort,
        )

    fun createZoneRegistry(
        config: AppConfig,
        redisManager: RedisConnectionManager?,
        engineId: String,
        engineAddress: EngineAddress,
        localZones: Set<String>,
    ): ZoneRegistry? =
        if (!config.sharding.enabled) {
            null
        } else {
            when (config.sharding.registry.type) {
                ShardingRegistryType.REDIS -> {
                    val manager = requireNotNull(redisManager) {
                        "Sharding registry type REDIS requires ambonMUD.redis.enabled=true"
                    }
                    if (config.sharding.instancing.enabled) {
                        InstancedRedisZoneRegistry(
                            redis = manager,
                            mapper = redisObjectMapper,
                            leaseTtlSeconds = config.sharding.registry.leaseTtlSeconds,
                            defaultCapacity = config.sharding.instancing.defaultCapacity,
                        )
                    } else {
                        ClassicRedisZoneRegistry(
                            redis = manager,
                            mapper = redisObjectMapper,
                            leaseTtlSeconds = config.sharding.registry.leaseTtlSeconds,
                        )
                    }
                }

                ShardingRegistryType.STATIC -> {
                    val configuredAssignments = config.sharding.registry.assignments
                    val assignmentMap =
                        if (configuredAssignments.isEmpty()) {
                            mapOf(engineId to Pair(engineAddress, localZones))
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
        }

    fun createInterEngineBus(
        config: AppConfig,
        redisManager: RedisConnectionManager?,
    ): InterEngineBus? =
        if (!config.sharding.enabled) {
            null
        } else if (redisManager != null) {
            RedisInterEngineBus(
                engineId = config.sharding.engineId,
                publisher = redisBusPublisher(redisManager),
                subscriberSetup = redisBusSubscriberSetup(redisManager),
                mapper = redisObjectMapper,
            )
        } else {
            LocalInterEngineBus()
        }

    fun createInstanceSelector(
        config: AppConfig,
        zoneRegistry: ZoneRegistry?,
    ): InstanceSelector? =
        if (config.sharding.enabled && config.sharding.instancing.enabled && zoneRegistry != null) {
            LoadBalancedInstanceSelector(zoneRegistry)
        } else {
            null
        }

    fun createPlayerLocationIndex(
        config: AppConfig,
        redisManager: RedisConnectionManager?,
    ): PlayerLocationIndex? =
        if (config.sharding.enabled && config.sharding.playerIndex.enabled && redisManager != null) {
            val keyTtlSeconds = (config.sharding.playerIndex.heartbeatMs * 3L / 1_000L).coerceAtLeast(1L)
            RedisPlayerLocationIndex(
                engineId = config.sharding.engineId,
                redis = redisManager,
                keyTtlSeconds = keyTtlSeconds,
            )
        } else {
            null
        }

    fun createHandoffManager(
        config: AppConfig,
        players: PlayerRegistry,
        items: ItemRegistry,
        outbound: OutboundBus,
        interEngineBus: InterEngineBus?,
        zoneRegistry: ZoneRegistry?,
        world: World,
        clock: Clock,
        instanceSelector: InstanceSelector?,
    ): HandoffManager? =
        if (config.sharding.enabled && interEngineBus != null && zoneRegistry != null) {
            HandoffManager(
                engineId = config.sharding.engineId,
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

    fun buildShardingContext(
        config: AppConfig,
        handoffManager: HandoffManager?,
        interEngineBus: InterEngineBus?,
        zoneRegistry: ZoneRegistry?,
        playerLocationIndex: PlayerLocationIndex?,
    ): ShardingContext {
        val engineId = config.sharding.engineId
        return ShardingContext(
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
        )
    }

    /**
     * Holder for the background jobs started by [startShardingJobs].
     * The caller is responsible for cancelling these on shutdown.
     */
    data class ShardingJobs(
        val zoneHeartbeat: Job? = null,
        val playerIndexHeartbeat: Job? = null,
        val zoneLoadReport: Job? = null,
        val autoScale: Job? = null,
    ) {
        /** Cancels all jobs, ignoring individual failures. */
        suspend fun stopAll() {
            runCatching { zoneHeartbeat?.cancelAndJoin() }
            runCatching { playerIndexHeartbeat?.cancelAndJoin() }
            runCatching { zoneLoadReport?.cancelAndJoin() }
            runCatching { autoScale?.cancelAndJoin() }
        }
    }

    /**
     * Starts the periodic background jobs for sharding: zone lease heartbeat,
     * player-location index heartbeat, zone load reporting, and auto-scale evaluation.
     */
    suspend fun startShardingJobs(
        config: AppConfig,
        scope: CoroutineScope,
        zoneRegistry: ZoneRegistry?,
        playerLocationIndex: PlayerLocationIndex?,
        interEngineBus: InterEngineBus?,
        redisManager: RedisConnectionManager?,
        players: PlayerRegistry,
        engineAddress: EngineAddress,
        localZones: Set<String>,
        world: World,
        clock: Clock,
    ): ShardingJobs {
        val engineId = config.sharding.engineId

        interEngineBus?.start()

        val playerIndexHeartbeatJob =
            if (playerLocationIndex != null) {
                scope.launchPeriodic(
                    config.sharding.playerIndex.heartbeatMs,
                    "Player location index heartbeat",
                ) {
                    playerLocationIndex.refreshTtls()
                }
            } else {
                null
            }

        if (zoneRegistry == null) {
            return ShardingJobs(playerIndexHeartbeat = playerIndexHeartbeatJob)
        }

        zoneRegistry.claimZones(engineId, engineAddress, localZones)
        val heartbeatIntervalMs =
            ((config.sharding.registry.leaseTtlSeconds * 1_000L) / 3L)
                .coerceAtLeast(1_000L)
        val zoneHeartbeatJob =
            scope.launchPeriodic(heartbeatIntervalMs, "Zone lease heartbeat (engine=$engineId)") {
                zoneRegistry.renewLease(engineId)
                zoneRegistry.claimZones(engineId, engineAddress, localZones)
            }

        val zoneLoadReportJob =
            if (config.sharding.instancing.enabled) {
                scope.launchPeriodic(
                    config.sharding.instancing.loadReportIntervalMs,
                    "Zone load report (engine=$engineId)",
                ) {
                    val zoneCounts =
                        players
                            .allPlayers()
                            .groupBy { it.roomId.zone }
                            .mapValues { (_, ps) -> ps.size }
                    zoneRegistry.reportLoad(engineId, zoneCounts)
                }
            } else {
                null
            }

        val autoScaleJob =
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
                    redisManager?.withCommands { RedisScaleDecisionPublisher(it) }
                        ?: LoggingScaleDecisionPublisher()
                scope.launchPeriodic(
                    config.sharding.instancing.autoScale.evaluationIntervalMs,
                    "Auto-scale evaluation",
                ) {
                    val decisions = scaler.evaluate()
                    if (decisions.isNotEmpty()) {
                        publisher.publish(decisions)
                    }
                }
            } else {
                null
            }

        return ShardingJobs(
            zoneHeartbeat = zoneHeartbeatJob,
            playerIndexHeartbeat = playerIndexHeartbeatJob,
            zoneLoadReport = zoneLoadReportJob,
            autoScale = autoScaleJob,
        )
    }
}
