package dev.ambon.config

data class AmbonMudConfig(
    val deployment: DeploymentConfig = DeploymentConfig(),
    val gameplay: GameplayConfig = GameplayConfig(),
)

data class DeploymentConfig(
    val telnetPort: Int = 4000,
    val webPort: Int = 8080,
    val webHost: String = "0.0.0.0",
    val webClientUrl: String = "http://localhost:8080",
    val playerDataDir: String = "data/players",
    val worldResources: List<String> = listOf("world/demo_ruins.yaml"),
    val inboundChannelCapacity: Int = 10_000,
    val outboundChannelCapacity: Int = 10_000,
    val sessionOutboundQueueCapacity: Int = 200,
    val telnetLineMaxLength: Int = 1024,
    val telnetMaxNonPrintablePerLine: Int = 32,
    val telnetReadBufferBytes: Int = 4096,
    val promptText: String = "> ",
    val webStopGracePeriodMillis: Long = 1_000L,
    val webStopTimeoutMillis: Long = 2_000L,
    val webMaxCloseReasonLength: Int = 123,
    val demoAutoLaunchBrowser: Boolean = false,
)

data class GameplayConfig(
    val engineTickMillis: Long = 100L,
    val schedulerMaxActionsPerTick: Int = 100,
    val loginMaxWrongPasswordRetries: Int = 3,
    val loginMaxFailedLoginAttemptsBeforeDisconnect: Int = 3,
    val mob: MobGameplayConfig = MobGameplayConfig(),
    val combat: CombatGameplayConfig = CombatGameplayConfig(),
    val regen: RegenGameplayConfig = RegenGameplayConfig(),
)

data class MobGameplayConfig(
    val minWanderDelayMillis: Long = 5_000L,
    val maxWanderDelayMillis: Long = 12_000L,
    val maxMovesPerTick: Int = 10,
)

data class CombatGameplayConfig(
    val tickMillis: Long = 1_000L,
    val minDamage: Int = 1,
    val maxDamage: Int = 4,
    val maxCombatsPerTick: Int = 20,
)

data class RegenGameplayConfig(
    val baseIntervalMs: Long = 5_000L,
    val minIntervalMs: Long = 1_000L,
    val msPerConstitution: Long = 200L,
    val regenAmount: Int = 1,
    val maxPlayersPerTick: Int = 50,
)

class AmbonMudConfigException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
