package dev.ambon.config

/** Selects the player persistence backend. */
enum class PersistenceBackend { YAML, POSTGRES }

/** Deployment mode controlling which components are started. */
enum class DeploymentMode {
    /** All components in a single process (default, current behaviour). */
    STANDALONE,

    /** Game engine + persistence + gRPC server; no transports. */
    ENGINE,

    /** Transports + OutboundRouter + gRPC client to a remote engine; no local engine/persistence. */
    GATEWAY,
}

data class AmbonMUDRootConfig(
    val ambonMUD: AppConfig = AppConfig(),
)

data class AppConfig(
    val mode: DeploymentMode = DeploymentMode.STANDALONE,
    val server: ServerConfig = ServerConfig(),
    val world: WorldConfig = WorldConfig(),
    val persistence: PersistenceConfig = PersistenceConfig(),
    val login: LoginConfig = LoginConfig(),
    val engine: EngineConfig = EngineConfig(),
    val progression: ProgressionConfig = ProgressionConfig(),
    val transport: TransportConfig = TransportConfig(),
    val demo: DemoConfig = DemoConfig(),
    val observability: ObservabilityConfig = ObservabilityConfig(),
    val logging: LoggingConfig = LoggingConfig(),
    val database: DatabaseConfig = DatabaseConfig(),
    val redis: RedisConfig = RedisConfig(),
    val grpc: GrpcConfig = GrpcConfig(),
    val gateway: GatewayConfig = GatewayConfig(),
    val sharding: ShardingConfig = ShardingConfig(),
) {
    fun validated(): AppConfig {
        require(server.telnetPort in 1..65535) { "ambonMUD.server.telnetPort must be between 1 and 65535" }
        require(server.webPort in 1..65535) { "ambonMUD.server.webPort must be between 1 and 65535" }
        require(server.inboundChannelCapacity > 0) { "ambonMUD.server.inboundChannelCapacity must be > 0" }
        require(server.outboundChannelCapacity > 0) { "ambonMUD.server.outboundChannelCapacity must be > 0" }
        require(server.sessionOutboundQueueCapacity > 0) { "ambonMUD.server.sessionOutboundQueueCapacity must be > 0" }
        require(server.maxInboundEventsPerTick > 0) { "ambonMUD.server.maxInboundEventsPerTick must be > 0" }
        require(server.tickMillis > 0L) { "ambonMUD.server.tickMillis must be > 0" }

        require(world.resources.isNotEmpty()) { "ambonMUD.world.resources must not be empty" }
        require(world.resources.all { it.isNotBlank() }) { "ambonMUD.world.resources entries must be non-blank" }

        require(persistence.rootDir.isNotBlank()) { "ambonMUD.persistence.rootDir must be non-blank" }
        require(persistence.worker.flushIntervalMs > 0L) { "ambonMUD.persistence.worker.flushIntervalMs must be > 0" }

        if (persistence.backend == PersistenceBackend.POSTGRES) {
            require(database.jdbcUrl.isNotBlank()) { "ambonMUD.database.jdbcUrl required when backend=POSTGRES" }
            require(database.maxPoolSize > 0) { "ambonMUD.database.maxPoolSize must be > 0" }
        }

        require(login.maxWrongPasswordRetries >= 0) { "ambonMUD.login.maxWrongPasswordRetries must be >= 0" }
        require(login.maxFailedAttemptsBeforeDisconnect > 0) {
            "ambonMUD.login.maxFailedAttemptsBeforeDisconnect must be > 0"
        }

        require(engine.mob.maxMovesPerTick > 0) { "ambonMUD.engine.mob.maxMovesPerTick must be > 0" }
        require(engine.mob.minWanderDelayMillis >= 0L) { "ambonMUD.engine.mob.minWanderDelayMillis must be >= 0" }
        require(engine.mob.maxWanderDelayMillis >= engine.mob.minWanderDelayMillis) {
            "ambonMUD.engine.mob.maxWanderDelayMillis must be >= minWanderDelayMillis"
        }
        require(engine.mob.maxWanderDelayMillis - engine.mob.minWanderDelayMillis <= Int.MAX_VALUE.toLong()) {
            "ambonMUD.engine.mob wander delay range (max - min) must not exceed Int.MAX_VALUE ms"
        }
        validateMobTier("weak", engine.mob.tiers.weak)
        validateMobTier("standard", engine.mob.tiers.standard)
        validateMobTier("elite", engine.mob.tiers.elite)
        validateMobTier("boss", engine.mob.tiers.boss)

        require(engine.combat.maxCombatsPerTick > 0) { "ambonMUD.engine.combat.maxCombatsPerTick must be > 0" }
        require(engine.combat.tickMillis > 0L) { "ambonMUD.engine.combat.tickMillis must be > 0" }
        require(engine.combat.minDamage > 0) { "ambonMUD.engine.combat.minDamage must be > 0" }
        require(engine.combat.maxDamage >= engine.combat.minDamage) {
            "ambonMUD.engine.combat.maxDamage must be >= minDamage"
        }
        require(!engine.combat.feedback.roomBroadcastEnabled || engine.combat.feedback.enabled) {
            "ambonMUD.engine.combat.feedback.roomBroadcastEnabled requires feedback.enabled=true"
        }
        require(engine.combat.strDivisor > 0) { "ambonMUD.engine.combat.strDivisor must be > 0" }
        require(engine.combat.dexDodgePerPoint >= 0) { "ambonMUD.engine.combat.dexDodgePerPoint must be >= 0" }
        require(engine.combat.maxDodgePercent in 0..100) { "ambonMUD.engine.combat.maxDodgePercent must be in 0..100" }
        require(engine.combat.intSpellDivisor > 0) { "ambonMUD.engine.combat.intSpellDivisor must be > 0" }

        require(engine.regen.maxPlayersPerTick > 0) { "ambonMUD.engine.regen.maxPlayersPerTick must be > 0" }
        require(engine.regen.baseIntervalMillis > 0L) { "ambonMUD.engine.regen.baseIntervalMillis must be > 0" }
        require(engine.regen.minIntervalMillis > 0L) { "ambonMUD.engine.regen.minIntervalMillis must be > 0" }
        require(engine.regen.msPerConstitution >= 0L) { "ambonMUD.engine.regen.msPerConstitution must be >= 0" }
        require(engine.regen.regenAmount > 0) { "ambonMUD.engine.regen.regenAmount must be > 0" }

        require(engine.scheduler.maxActionsPerTick > 0) { "ambonMUD.engine.scheduler.maxActionsPerTick must be > 0" }

        require(engine.regen.mana.baseIntervalMillis > 0L) { "ambonMUD.engine.regen.mana.baseIntervalMillis must be > 0" }
        require(engine.regen.mana.minIntervalMillis > 0L) { "ambonMUD.engine.regen.mana.minIntervalMillis must be > 0" }
        require(engine.regen.mana.regenAmount > 0) { "ambonMUD.engine.regen.mana.regenAmount must be > 0" }
        require(engine.regen.mana.msPerWisdom >= 0L) { "ambonMUD.engine.regen.mana.msPerWisdom must be >= 0" }

        engine.abilities.definitions.forEach { (key, def) ->
            require(def.displayName.isNotBlank()) { "ability '$key' displayName must be non-blank" }
            require(def.manaCost >= 0) { "ability '$key' manaCost must be >= 0" }
            require(def.cooldownMs >= 0L) { "ability '$key' cooldownMs must be >= 0" }
            require(def.levelRequired >= 1) { "ability '$key' levelRequired must be >= 1" }
            require(def.targetType.uppercase() in listOf("ENEMY", "SELF")) {
                "ability '$key' targetType must be ENEMY or SELF, got '${def.targetType}'"
            }
            require(def.effect.type.uppercase() in listOf("DIRECT_DAMAGE", "DIRECT_HEAL")) {
                "ability '$key' effect.type must be DIRECT_DAMAGE or DIRECT_HEAL, got '${def.effect.type}'"
            }
        }

        require(progression.maxLevel > 0) { "ambonMUD.progression.maxLevel must be > 0" }
        require(progression.xp.baseXp > 0L) { "ambonMUD.progression.xp.baseXp must be > 0" }
        require(progression.xp.exponent > 0.0) { "ambonMUD.progression.xp.exponent must be > 0" }
        require(progression.xp.linearXp >= 0L) { "ambonMUD.progression.xp.linearXp must be >= 0" }
        require(progression.xp.multiplier >= 0.0) { "ambonMUD.progression.xp.multiplier must be >= 0" }
        require(progression.xp.defaultKillXp >= 0L) { "ambonMUD.progression.xp.defaultKillXp must be >= 0" }
        require(progression.rewards.hpPerLevel >= 0) { "ambonMUD.progression.rewards.hpPerLevel must be >= 0" }
        require(progression.rewards.manaPerLevel >= 0) { "ambonMUD.progression.rewards.manaPerLevel must be >= 0" }

        require(transport.telnet.maxLineLen > 0) { "ambonMUD.transport.telnet.maxLineLen must be > 0" }
        require(transport.telnet.maxNonPrintablePerLine >= 0) {
            "ambonMUD.transport.telnet.maxNonPrintablePerLine must be >= 0"
        }
        require(transport.maxInboundBackpressureFailures > 0) {
            "ambonMUD.transport.maxInboundBackpressureFailures must be > 0"
        }

        require(transport.websocket.host.isNotBlank()) { "ambonMUD.transport.websocket.host must be non-blank" }
        require(transport.websocket.stopGraceMillis >= 0L) { "ambonMUD.transport.websocket.stopGraceMillis must be >= 0" }
        require(transport.websocket.stopTimeoutMillis >= 0L) { "ambonMUD.transport.websocket.stopTimeoutMillis must be >= 0" }

        require(demo.webClientHost.isNotBlank()) { "ambonMUD.demo.webClientHost must be non-blank" }

        require(observability.metricsEndpoint.startsWith("/")) {
            "ambonMUD.observability.metricsEndpoint must start with '/'"
        }
        require(observability.metricsHttpPort in 1..65535) {
            "ambonMUD.observability.metricsHttpPort must be between 1 and 65535"
        }

        if (redis.enabled) {
            require(redis.uri.isNotBlank()) { "ambonMUD.redis.uri must be non-blank when redis.enabled=true" }
            require(redis.cacheTtlSeconds > 0L) { "ambonMUD.redis.cacheTtlSeconds must be > 0" }
        }

        if (mode == DeploymentMode.ENGINE || mode == DeploymentMode.GATEWAY) {
            require(grpc.server.port in 1..65535) { "ambonMUD.grpc.server.port must be between 1 and 65535" }
        }

        if (mode == DeploymentMode.GATEWAY) {
            require(grpc.client.engineHost.isNotBlank()) { "ambonMUD.grpc.client.engineHost must be non-blank in gateway mode" }
            require(grpc.client.enginePort in 1..65535) { "ambonMUD.grpc.client.enginePort must be between 1 and 65535" }
            require(gateway.id in 0..0xFFFF) { "ambonMUD.gateway.id must be between 0 and 65535" }
            require(gateway.snowflake.idLeaseTtlSeconds > 0L) {
                "ambonMUD.gateway.snowflake.idLeaseTtlSeconds must be > 0"
            }
            require(gateway.reconnect.maxAttempts > 0) {
                "ambonMUD.gateway.reconnect.maxAttempts must be > 0"
            }
            require(gateway.reconnect.initialDelayMs > 0) {
                "ambonMUD.gateway.reconnect.initialDelayMs must be > 0"
            }
            require(gateway.reconnect.maxDelayMs >= gateway.reconnect.initialDelayMs) {
                "ambonMUD.gateway.reconnect.maxDelayMs must be >= initialDelayMs"
            }
            require(gateway.reconnect.jitterFactor in 0.0..1.0) {
                "ambonMUD.gateway.reconnect.jitterFactor must be in 0.0..1.0"
            }
            require(gateway.reconnect.streamVerifyMs > 0) {
                "ambonMUD.gateway.reconnect.streamVerifyMs must be > 0"
            }

            val seenGatewayEngineIds = mutableSetOf<String>()
            gateway.engines.forEachIndexed { idx, entry ->
                require(entry.id.isNotBlank()) { "ambonMUD.gateway.engines[$idx].id must be non-blank" }
                require(entry.host.isNotBlank()) { "ambonMUD.gateway.engines[$idx].host must be non-blank" }
                require(entry.port in 1..65535) { "ambonMUD.gateway.engines[$idx].port must be between 1 and 65535" }
                require(seenGatewayEngineIds.add(entry.id)) {
                    "ambonMUD.gateway.engines contains duplicate id '${entry.id}'"
                }
            }
        }

        if (sharding.enabled) {
            require(sharding.engineId.isNotBlank()) { "ambonMUD.sharding.engineId must be non-blank when sharding.enabled=true" }
            require(sharding.handoff.ackTimeoutMs > 0L) { "ambonMUD.sharding.handoff.ackTimeoutMs must be > 0" }
            require(sharding.registry.leaseTtlSeconds > 0L) {
                "ambonMUD.sharding.registry.leaseTtlSeconds must be > 0"
            }
            require(sharding.advertiseHost.isNotBlank()) {
                "ambonMUD.sharding.advertiseHost must be non-blank when sharding.enabled=true"
            }
            sharding.advertisePort?.let { port ->
                require(port in 1..65535) { "ambonMUD.sharding.advertisePort must be between 1 and 65535" }
            }

            val seenAssignmentEngineIds = mutableSetOf<String>()
            val seenAssignedZones = mutableSetOf<String>()
            sharding.registry.assignments.forEachIndexed { idx, assignment ->
                require(assignment.engineId.isNotBlank()) {
                    "ambonMUD.sharding.registry.assignments[$idx].engineId must be non-blank"
                }
                require(assignment.host.isNotBlank()) {
                    "ambonMUD.sharding.registry.assignments[$idx].host must be non-blank"
                }
                require(assignment.port in 1..65535) {
                    "ambonMUD.sharding.registry.assignments[$idx].port must be between 1 and 65535"
                }
                require(seenAssignmentEngineIds.add(assignment.engineId)) {
                    "ambonMUD.sharding.registry.assignments contains duplicate engineId '${assignment.engineId}'"
                }

                assignment.zones.forEach { zone ->
                    require(zone.isNotBlank()) {
                        "ambonMUD.sharding.registry.assignments[$idx].zones entries must be non-blank"
                    }
                    require(seenAssignedZones.add(zone)) {
                        "Zone '$zone' is assigned more than once in ambonMUD.sharding.registry.assignments"
                    }
                }
            }

            if (sharding.playerIndex.enabled) {
                require(sharding.playerIndex.heartbeatMs > 0L) {
                    "ambonMUD.sharding.playerIndex.heartbeatMs must be > 0"
                }
            }
        }

        return this
    }
}

data class ServerConfig(
    val telnetPort: Int = 4000,
    val webPort: Int = 8080,
    val inboundChannelCapacity: Int = 10_000,
    val outboundChannelCapacity: Int = 10_000,
    val sessionOutboundQueueCapacity: Int = 200,
    val maxInboundEventsPerTick: Int = 1_000,
    val tickMillis: Long = 100L,
)

data class WorldConfig(
    val resources: List<String> = listOf("world/demo_ruins.yaml"),
)

data class PersistenceConfig(
    val backend: PersistenceBackend = PersistenceBackend.YAML,
    val rootDir: String = "data/players",
    val worker: PersistenceWorkerConfig = PersistenceWorkerConfig(),
)

data class PersistenceWorkerConfig(
    val enabled: Boolean = true,
    val flushIntervalMs: Long = 5_000L,
)

data class DatabaseConfig(
    val jdbcUrl: String = "jdbc:postgresql://localhost:5432/ambonmud",
    val username: String = "ambon",
    val password: String = "ambon",
    val maxPoolSize: Int = 5,
    val minimumIdle: Int = 1,
)

data class LoginConfig(
    val maxWrongPasswordRetries: Int = 3,
    val maxFailedAttemptsBeforeDisconnect: Int = 3,
)

data class EngineConfig(
    val mob: MobEngineConfig = MobEngineConfig(),
    val combat: CombatEngineConfig = CombatEngineConfig(),
    val regen: RegenEngineConfig = RegenEngineConfig(),
    val scheduler: SchedulerEngineConfig = SchedulerEngineConfig(),
    val abilities: AbilityEngineConfig = AbilityEngineConfig(),
)

data class ProgressionConfig(
    val maxLevel: Int = 50,
    val xp: XpCurveConfig = XpCurveConfig(),
    val rewards: LevelRewardsConfig = LevelRewardsConfig(),
)

data class XpCurveConfig(
    val baseXp: Long = 100L,
    val exponent: Double = 2.0,
    val linearXp: Long = 0L,
    val multiplier: Double = 1.0,
    val defaultKillXp: Long = 50L,
)

data class LevelRewardsConfig(
    val hpPerLevel: Int = 2,
    val manaPerLevel: Int = 5,
    val fullHealOnLevelUp: Boolean = true,
    val fullManaOnLevelUp: Boolean = true,
)

data class MobTierConfig(
    val baseHp: Int = 10,
    val hpPerLevel: Int = 3,
    val baseMinDamage: Int = 1,
    val baseMaxDamage: Int = 4,
    val damagePerLevel: Int = 1,
    val baseArmor: Int = 0,
    val baseXpReward: Long = 30L,
    val xpRewardPerLevel: Long = 10L,
)

data class MobTiersConfig(
    val weak: MobTierConfig =
        MobTierConfig(
            baseHp = 5,
            hpPerLevel = 2,
            baseMinDamage = 1,
            baseMaxDamage = 2,
            damagePerLevel = 0,
            baseArmor = 0,
            baseXpReward = 15L,
            xpRewardPerLevel = 5L,
        ),
    val standard: MobTierConfig =
        MobTierConfig(
            baseHp = 10,
            hpPerLevel = 3,
            baseMinDamage = 1,
            baseMaxDamage = 4,
            damagePerLevel = 1,
            baseArmor = 0,
            baseXpReward = 30L,
            xpRewardPerLevel = 10L,
        ),
    val elite: MobTierConfig =
        MobTierConfig(
            baseHp = 20,
            hpPerLevel = 5,
            baseMinDamage = 2,
            baseMaxDamage = 6,
            damagePerLevel = 1,
            baseArmor = 1,
            baseXpReward = 75L,
            xpRewardPerLevel = 20L,
        ),
    val boss: MobTierConfig =
        MobTierConfig(
            baseHp = 50,
            hpPerLevel = 10,
            baseMinDamage = 3,
            baseMaxDamage = 8,
            damagePerLevel = 2,
            baseArmor = 3,
            baseXpReward = 200L,
            xpRewardPerLevel = 50L,
        ),
) {
    fun forName(name: String): MobTierConfig? =
        when (name.lowercase()) {
            "weak" -> weak
            "standard" -> standard
            "elite" -> elite
            "boss" -> boss
            else -> null
        }
}

data class MobEngineConfig(
    val maxMovesPerTick: Int = 10,
    val minWanderDelayMillis: Long = 5_000L,
    val maxWanderDelayMillis: Long = 12_000L,
    val tiers: MobTiersConfig = MobTiersConfig(),
)

data class CombatEngineConfig(
    val maxCombatsPerTick: Int = 20,
    val tickMillis: Long = 1_000L,
    val minDamage: Int = 1,
    val maxDamage: Int = 4,
    val feedback: CombatFeedbackConfig = CombatFeedbackConfig(),
    val strDivisor: Int = 3,
    val dexDodgePerPoint: Int = 2,
    val maxDodgePercent: Int = 30,
    val intSpellDivisor: Int = 3,
)

data class CombatFeedbackConfig(
    val enabled: Boolean = false,
    val roomBroadcastEnabled: Boolean = false,
)

data class RegenEngineConfig(
    val maxPlayersPerTick: Int = 50,
    val baseIntervalMillis: Long = 5_000L,
    val minIntervalMillis: Long = 1_000L,
    val msPerConstitution: Long = 200L,
    val regenAmount: Int = 1,
    val mana: ManaRegenConfig = ManaRegenConfig(),
)

data class ManaRegenConfig(
    val baseIntervalMillis: Long = 3_000L,
    val minIntervalMillis: Long = 1_000L,
    val regenAmount: Int = 1,
    val msPerWisdom: Long = 200L,
)

data class SchedulerEngineConfig(
    val maxActionsPerTick: Int = 100,
)

data class AbilityEngineConfig(
    val definitions: Map<String, AbilityDefinitionConfig> = emptyMap(),
)

data class AbilityDefinitionConfig(
    val displayName: String = "",
    val description: String = "",
    val manaCost: Int = 10,
    val cooldownMs: Long = 0L,
    val levelRequired: Int = 1,
    val targetType: String = "ENEMY",
    val effect: AbilityEffectConfig = AbilityEffectConfig(),
)

data class AbilityEffectConfig(
    val type: String = "DIRECT_DAMAGE",
    val minDamage: Int = 0,
    val maxDamage: Int = 0,
    val minHeal: Int = 0,
    val maxHeal: Int = 0,
)

data class TransportConfig(
    val telnet: TelnetTransportConfig = TelnetTransportConfig(),
    val websocket: WebSocketTransportConfig = WebSocketTransportConfig(),
    val maxInboundBackpressureFailures: Int = 3,
)

data class TelnetTransportConfig(
    val maxLineLen: Int = 1024,
    val maxNonPrintablePerLine: Int = 32,
)

data class WebSocketTransportConfig(
    val host: String = "0.0.0.0",
    val stopGraceMillis: Long = 1_000L,
    val stopTimeoutMillis: Long = 2_000L,
)

data class DemoConfig(
    val autoLaunchBrowser: Boolean = false,
    val webClientHost: String = "localhost",
    val webClientUrl: String? = null,
)

data class ObservabilityConfig(
    val metricsEnabled: Boolean = true,
    val metricsEndpoint: String = "/metrics",
    val metricsHttpPort: Int = 9090,
    val staticTags: Map<String, String> = emptyMap(),
)

data class LoggingConfig(
    val level: String = "INFO",
    val packageLevels: Map<String, String> = emptyMap(),
)

data class GrpcServerConfig(
    val port: Int = 9090,
)

data class GrpcClientConfig(
    val engineHost: String = "localhost",
    val enginePort: Int = 9090,
)

data class GrpcConfig(
    val server: GrpcServerConfig = GrpcServerConfig(),
    val client: GrpcClientConfig = GrpcClientConfig(),
)

/** Snowflake session-ID hardening settings (used by GATEWAY mode). */
data class SnowflakeConfig(
    /** TTL in seconds for the Redis gateway-ID exclusive lease. */
    val idLeaseTtlSeconds: Long = 300L,
)

/** Reconnect/backoff settings for the gateway → engine gRPC stream. */
data class GatewayReconnectConfig(
    val maxAttempts: Int = 10,
    val initialDelayMs: Long = 1_000L,
    val maxDelayMs: Long = 30_000L,
    val jitterFactor: Double = 0.2,
    val streamVerifyMs: Long = 2_000L,
)

/** Gateway-specific settings. */
data class GatewayConfig(
    /** 16-bit gateway ID for [SnowflakeSessionIdFactory] bit-field (0–65535). */
    val id: Int = 0,
    val snowflake: SnowflakeConfig = SnowflakeConfig(),
    val reconnect: GatewayReconnectConfig = GatewayReconnectConfig(),
    /** Static list of engines for multi-engine mode. Empty = single engine via grpc.client config. */
    val engines: List<GatewayEngineEntry> = emptyList(),
)

/** Address entry for a remote engine in multi-engine gateway mode. */
data class GatewayEngineEntry(
    val id: String,
    val host: String,
    val port: Int,
)

data class RedisBusConfig(
    val enabled: Boolean = false,
    val inboundChannel: String = "ambon:inbound",
    val outboundChannel: String = "ambon:outbound",
    val instanceId: String = "",
)

data class RedisConfig(
    val enabled: Boolean = false,
    val uri: String = "redis://localhost:6379",
    val cacheTtlSeconds: Long = 3600L,
    val bus: RedisBusConfig = RedisBusConfig(),
)

enum class ShardingRegistryType {
    STATIC,
    REDIS,
}

data class ShardingRegistryAssignment(
    val engineId: String,
    val host: String,
    val port: Int,
    val zones: List<String> = emptyList(),
)

data class ShardingRegistryConfig(
    val type: ShardingRegistryType = ShardingRegistryType.STATIC,
    val leaseTtlSeconds: Long = 30L,
    val assignments: List<ShardingRegistryAssignment> = emptyList(),
)

data class ShardingHandoffConfig(
    val ackTimeoutMs: Long = 2_000L,
)

data class PlayerIndexConfig(
    /** Enable the Redis player-location index for O(1) cross-engine tell routing. */
    val enabled: Boolean = false,
    /** How often (ms) to refresh key TTLs for online players. */
    val heartbeatMs: Long = 10_000L,
)

/** Zone-based engine sharding settings. */
data class ShardingConfig(
    /** Enable zone-based sharding. When false, the engine loads all zones (default). */
    val enabled: Boolean = false,
    /** Unique identifier for this engine instance. Used for inter-engine messaging and zone ownership. */
    val engineId: String = "engine-1",
    /** Zones this engine owns. Empty list = all zones (single-engine backward compat). */
    val zones: List<String> = emptyList(),
    /** Registry settings for mapping zones to owning engines. */
    val registry: ShardingRegistryConfig = ShardingRegistryConfig(),
    /** Cross-engine handoff behavior. */
    val handoff: ShardingHandoffConfig = ShardingHandoffConfig(),
    /** Host advertised in zone ownership records for this engine. */
    val advertiseHost: String = "localhost",
    /** Optional advertised port override. Defaults to mode-specific port when null. */
    val advertisePort: Int? = null,
    /** Redis player-location index for O(1) cross-engine tell routing. */
    val playerIndex: PlayerIndexConfig = PlayerIndexConfig(),
)

private fun validateMobTier(
    name: String,
    tier: MobTierConfig,
) {
    require(tier.baseHp > 0) { "ambonMUD.engine.mob.tiers.$name.baseHp must be > 0" }
    require(tier.hpPerLevel >= 0) { "ambonMUD.engine.mob.tiers.$name.hpPerLevel must be >= 0" }
    require(tier.baseMinDamage > 0) { "ambonMUD.engine.mob.tiers.$name.baseMinDamage must be > 0" }
    require(tier.baseMaxDamage >= tier.baseMinDamage) {
        "ambonMUD.engine.mob.tiers.$name.baseMaxDamage must be >= baseMinDamage"
    }
    require(tier.damagePerLevel >= 0) { "ambonMUD.engine.mob.tiers.$name.damagePerLevel must be >= 0" }
    require(tier.baseArmor >= 0) { "ambonMUD.engine.mob.tiers.$name.baseArmor must be >= 0" }
    require(tier.baseXpReward >= 0L) { "ambonMUD.engine.mob.tiers.$name.baseXpReward must be >= 0" }
    require(tier.xpRewardPerLevel >= 0L) { "ambonMUD.engine.mob.tiers.$name.xpRewardPerLevel must be >= 0" }
}
