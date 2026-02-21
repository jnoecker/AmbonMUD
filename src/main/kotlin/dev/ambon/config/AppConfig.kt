package dev.ambon.config

data class AmbonMUDRootConfig(
    val ambonMUD: AppConfig = AppConfig(),
)

data class AppConfig(
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

        require(engine.regen.maxPlayersPerTick > 0) { "ambonMUD.engine.regen.maxPlayersPerTick must be > 0" }
        require(engine.regen.baseIntervalMillis > 0L) { "ambonMUD.engine.regen.baseIntervalMillis must be > 0" }
        require(engine.regen.minIntervalMillis > 0L) { "ambonMUD.engine.regen.minIntervalMillis must be > 0" }
        require(engine.regen.msPerConstitution >= 0L) { "ambonMUD.engine.regen.msPerConstitution must be >= 0" }
        require(engine.regen.regenAmount > 0) { "ambonMUD.engine.regen.regenAmount must be > 0" }

        require(engine.scheduler.maxActionsPerTick > 0) { "ambonMUD.engine.scheduler.maxActionsPerTick must be > 0" }

        require(progression.maxLevel > 0) { "ambonMUD.progression.maxLevel must be > 0" }
        require(progression.xp.baseXp > 0L) { "ambonMUD.progression.xp.baseXp must be > 0" }
        require(progression.xp.exponent > 0.0) { "ambonMUD.progression.xp.exponent must be > 0" }
        require(progression.xp.linearXp >= 0L) { "ambonMUD.progression.xp.linearXp must be >= 0" }
        require(progression.xp.multiplier >= 0.0) { "ambonMUD.progression.xp.multiplier must be >= 0" }
        require(progression.xp.defaultKillXp >= 0L) { "ambonMUD.progression.xp.defaultKillXp must be >= 0" }
        require(progression.rewards.hpPerLevel >= 0) { "ambonMUD.progression.rewards.hpPerLevel must be >= 0" }

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
    val rootDir: String = "data/players",
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
    val fullHealOnLevelUp: Boolean = true,
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
)

data class RegenEngineConfig(
    val maxPlayersPerTick: Int = 50,
    val baseIntervalMillis: Long = 5_000L,
    val minIntervalMillis: Long = 1_000L,
    val msPerConstitution: Long = 200L,
    val regenAmount: Int = 1,
)

data class SchedulerEngineConfig(
    val maxActionsPerTick: Int = 100,
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
    val staticTags: Map<String, String> = emptyMap(),
)

data class LoggingConfig(
    val level: String = "INFO",
    val packageLevels: Map<String, String> = emptyMap(),
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
