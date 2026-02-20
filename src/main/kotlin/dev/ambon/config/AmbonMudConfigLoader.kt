package dev.ambon.config

import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.ConfigSource
import com.sksamuel.hoplite.addMapSource
import com.sksamuel.hoplite.addPathSource
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths

object AmbonMudConfigLoader {
    private const val DEPLOYMENT_FILE_NAME = "ambonmud.yml"
    private const val GAMEPLAY_FILE_NAME = "ambonmud.gameplay.yml"
    private const val LEGACY_DEMO_AUTO_LAUNCH_PROP = "quickmud.demo.autolaunchBrowser"
    private val DEFAULT_WEB_CLIENT_URL = DeploymentConfig().webClientUrl

    fun load(
        baseDir: Path = Paths.get("").toAbsolutePath(),
        env: Map<String, String> = System.getenv(),
        systemProperties: Map<String, String> = currentSystemProperties(),
    ): AmbonMudConfig {
        val loader =
            ConfigLoaderBuilder
                .empty()
                .addDefaults()
                .allowEmptyConfigFiles()
                // Hoplite uses "first source wins": earlier-registered sources override later ones.
                // Effective precedence is defaults < ambonmud.yml < ambonmud.gameplay.yml < env < JVM properties.
                .addMapSource(propertyOverrides(systemProperties))
                .addMapSource(envOverrides(env))
                .addPathSource(baseDir.resolve(GAMEPLAY_FILE_NAME), optional = true)
                .addPathSource(baseDir.resolve(DEPLOYMENT_FILE_NAME), optional = true)
                .build()

        val loaded =
            runCatching {
                loader.loadConfigOrThrow(
                    AmbonMudConfig::class,
                    emptyList<ConfigSource>(),
                    null,
                )
            }
                .getOrElse { throw AmbonMudConfigException("Failed to load AmbonMUD config: ${it.message}", it) }

        val config = alignWebClientUrlWithWebPort(loaded)
        validate(config)
        return config
    }

    private fun alignWebClientUrlWithWebPort(config: AmbonMudConfig): AmbonMudConfig {
        if (config.deployment.webClientUrl != DEFAULT_WEB_CLIENT_URL) return config
        val updatedUrl = "http://localhost:${config.deployment.webPort}"
        return config.copy(deployment = config.deployment.copy(webClientUrl = updatedUrl))
    }

    private fun envOverrides(env: Map<String, String>): Map<String, Any> =
        ENV_KEY_TO_CONFIG_KEY
            .mapNotNull { (envKey, configKey) -> env[envKey]?.let { configKey to normalizeOverrideValue(configKey, it) } }
            .toMap()

    private fun propertyOverrides(systemProperties: Map<String, String>): Map<String, Any> {
        val mapped =
            systemProperties.entries
                .filter { it.key.startsWith("ambonmud.") }
                .associate { (key, value) ->
                    val configKey = key.removePrefix("ambonmud.")
                    configKey to normalizeOverrideValue(configKey, value)
                }.toMutableMap()

        if (!mapped.containsKey("deployment.demoAutoLaunchBrowser")) {
            val legacy = systemProperties[LEGACY_DEMO_AUTO_LAUNCH_PROP]
            if (legacy != null) {
                mapped["deployment.demoAutoLaunchBrowser"] =
                    normalizeOverrideValue("deployment.demoAutoLaunchBrowser", legacy)
            }
        }

        return mapped
    }

    private fun normalizeOverrideValue(
        configKey: String,
        rawValue: String,
    ): Any =
        when (configKey) {
            "deployment.worldResources" -> parseStringList(rawValue)
            else -> rawValue
        }

    private fun parseStringList(raw: String): List<String> =
        raw
            .split(',', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun validate(config: AmbonMudConfig) {
        val deployment = config.deployment
        ensure(deployment.telnetPort in 1..65535, "deployment.telnetPort must be between 1 and 65535")
        ensure(deployment.webPort in 1..65535, "deployment.webPort must be between 1 and 65535")
        ensure(deployment.webHost.isNotBlank(), "deployment.webHost cannot be blank")

        val webClientUri =
            runCatching { URI(deployment.webClientUrl) }
                .getOrElse { throw AmbonMudConfigException("deployment.webClientUrl must be a valid URI", it) }
        ensure(!webClientUri.scheme.isNullOrBlank(), "deployment.webClientUrl must include a URI scheme")

        ensure(deployment.playerDataDir.isNotBlank(), "deployment.playerDataDir cannot be blank")
        ensure(deployment.worldResources.isNotEmpty(), "deployment.worldResources cannot be empty")
        ensure(
            deployment.worldResources.all { it.isNotBlank() },
            "deployment.worldResources cannot contain blank entries",
        )
        ensure(deployment.inboundChannelCapacity > 0, "deployment.inboundChannelCapacity must be > 0")
        ensure(deployment.outboundChannelCapacity > 0, "deployment.outboundChannelCapacity must be > 0")
        ensure(deployment.sessionOutboundQueueCapacity > 0, "deployment.sessionOutboundQueueCapacity must be > 0")
        ensure(deployment.telnetLineMaxLength > 0, "deployment.telnetLineMaxLength must be > 0")
        ensure(
            deployment.telnetMaxNonPrintablePerLine >= 0,
            "deployment.telnetMaxNonPrintablePerLine must be >= 0",
        )
        ensure(deployment.telnetReadBufferBytes > 0, "deployment.telnetReadBufferBytes must be > 0")
        ensure(deployment.promptText.isNotBlank(), "deployment.promptText cannot be blank")
        ensure(
            deployment.webStopGracePeriodMillis >= 0,
            "deployment.webStopGracePeriodMillis must be >= 0",
        )
        ensure(
            deployment.webStopTimeoutMillis >= deployment.webStopGracePeriodMillis,
            "deployment.webStopTimeoutMillis must be >= deployment.webStopGracePeriodMillis",
        )
        ensure(
            deployment.webMaxCloseReasonLength in 1..123,
            "deployment.webMaxCloseReasonLength must be between 1 and 123",
        )

        val gameplay = config.gameplay
        ensure(gameplay.engineTickMillis > 0, "gameplay.engineTickMillis must be > 0")
        ensure(gameplay.schedulerMaxActionsPerTick > 0, "gameplay.schedulerMaxActionsPerTick must be > 0")
        ensure(gameplay.loginMaxWrongPasswordRetries >= 0, "gameplay.loginMaxWrongPasswordRetries must be >= 0")
        ensure(
            gameplay.loginMaxFailedLoginAttemptsBeforeDisconnect > 0,
            "gameplay.loginMaxFailedLoginAttemptsBeforeDisconnect must be > 0",
        )
        ensure(gameplay.mob.minWanderDelayMillis >= 0, "gameplay.mob.minWanderDelayMillis must be >= 0")
        ensure(
            gameplay.mob.maxWanderDelayMillis >= gameplay.mob.minWanderDelayMillis,
            "gameplay.mob.maxWanderDelayMillis must be >= gameplay.mob.minWanderDelayMillis",
        )
        ensure(gameplay.mob.maxMovesPerTick > 0, "gameplay.mob.maxMovesPerTick must be > 0")
        ensure(gameplay.combat.tickMillis > 0, "gameplay.combat.tickMillis must be > 0")
        ensure(gameplay.combat.minDamage > 0, "gameplay.combat.minDamage must be > 0")
        ensure(
            gameplay.combat.maxDamage >= gameplay.combat.minDamage,
            "gameplay.combat.maxDamage must be >= gameplay.combat.minDamage",
        )
        ensure(gameplay.combat.maxCombatsPerTick > 0, "gameplay.combat.maxCombatsPerTick must be > 0")
        ensure(gameplay.regen.baseIntervalMs > 0, "gameplay.regen.baseIntervalMs must be > 0")
        ensure(gameplay.regen.minIntervalMs > 0, "gameplay.regen.minIntervalMs must be > 0")
        ensure(gameplay.regen.msPerConstitution >= 0, "gameplay.regen.msPerConstitution must be >= 0")
        ensure(gameplay.regen.regenAmount > 0, "gameplay.regen.regenAmount must be > 0")
        ensure(gameplay.regen.maxPlayersPerTick > 0, "gameplay.regen.maxPlayersPerTick must be > 0")
    }

    private fun ensure(
        condition: Boolean,
        message: String,
    ) {
        if (!condition) {
            throw AmbonMudConfigException(message)
        }
    }

    private fun currentSystemProperties(): Map<String, String> {
        val properties = System.getProperties()
        return properties.stringPropertyNames().associateWith { key -> properties.getProperty(key) }
    }

    private val ENV_KEY_TO_CONFIG_KEY =
        mapOf(
            "AMBONMUD_DEPLOYMENT_TELNET_PORT" to "deployment.telnetPort",
            "AMBONMUD_DEPLOYMENT_WEB_PORT" to "deployment.webPort",
            "AMBONMUD_DEPLOYMENT_WEB_HOST" to "deployment.webHost",
            "AMBONMUD_DEPLOYMENT_WEB_CLIENT_URL" to "deployment.webClientUrl",
            "AMBONMUD_DEPLOYMENT_PLAYER_DATA_DIR" to "deployment.playerDataDir",
            "AMBONMUD_DEPLOYMENT_WORLD_RESOURCES" to "deployment.worldResources",
            "AMBONMUD_DEPLOYMENT_INBOUND_CHANNEL_CAPACITY" to "deployment.inboundChannelCapacity",
            "AMBONMUD_DEPLOYMENT_OUTBOUND_CHANNEL_CAPACITY" to "deployment.outboundChannelCapacity",
            "AMBONMUD_DEPLOYMENT_SESSION_OUTBOUND_QUEUE_CAPACITY" to "deployment.sessionOutboundQueueCapacity",
            "AMBONMUD_DEPLOYMENT_TELNET_LINE_MAX_LENGTH" to "deployment.telnetLineMaxLength",
            "AMBONMUD_DEPLOYMENT_TELNET_MAX_NON_PRINTABLE_PER_LINE" to "deployment.telnetMaxNonPrintablePerLine",
            "AMBONMUD_DEPLOYMENT_TELNET_READ_BUFFER_BYTES" to "deployment.telnetReadBufferBytes",
            "AMBONMUD_DEPLOYMENT_PROMPT_TEXT" to "deployment.promptText",
            "AMBONMUD_DEPLOYMENT_WEB_STOP_GRACE_PERIOD_MILLIS" to "deployment.webStopGracePeriodMillis",
            "AMBONMUD_DEPLOYMENT_WEB_STOP_TIMEOUT_MILLIS" to "deployment.webStopTimeoutMillis",
            "AMBONMUD_DEPLOYMENT_WEB_MAX_CLOSE_REASON_LENGTH" to "deployment.webMaxCloseReasonLength",
            "AMBONMUD_DEPLOYMENT_DEMO_AUTO_LAUNCH_BROWSER" to "deployment.demoAutoLaunchBrowser",
            "AMBONMUD_GAMEPLAY_ENGINE_TICK_MILLIS" to "gameplay.engineTickMillis",
            "AMBONMUD_GAMEPLAY_SCHEDULER_MAX_ACTIONS_PER_TICK" to "gameplay.schedulerMaxActionsPerTick",
            "AMBONMUD_GAMEPLAY_LOGIN_MAX_WRONG_PASSWORD_RETRIES" to "gameplay.loginMaxWrongPasswordRetries",
            "AMBONMUD_GAMEPLAY_LOGIN_MAX_FAILED_LOGIN_ATTEMPTS_BEFORE_DISCONNECT" to "gameplay.loginMaxFailedLoginAttemptsBeforeDisconnect",
            "AMBONMUD_GAMEPLAY_MOB_MIN_WANDER_DELAY_MILLIS" to "gameplay.mob.minWanderDelayMillis",
            "AMBONMUD_GAMEPLAY_MOB_MAX_WANDER_DELAY_MILLIS" to "gameplay.mob.maxWanderDelayMillis",
            "AMBONMUD_GAMEPLAY_MOB_MAX_MOVES_PER_TICK" to "gameplay.mob.maxMovesPerTick",
            "AMBONMUD_GAMEPLAY_COMBAT_TICK_MILLIS" to "gameplay.combat.tickMillis",
            "AMBONMUD_GAMEPLAY_COMBAT_MIN_DAMAGE" to "gameplay.combat.minDamage",
            "AMBONMUD_GAMEPLAY_COMBAT_MAX_DAMAGE" to "gameplay.combat.maxDamage",
            "AMBONMUD_GAMEPLAY_COMBAT_MAX_COMBATS_PER_TICK" to "gameplay.combat.maxCombatsPerTick",
            "AMBONMUD_GAMEPLAY_REGEN_BASE_INTERVAL_MS" to "gameplay.regen.baseIntervalMs",
            "AMBONMUD_GAMEPLAY_REGEN_MIN_INTERVAL_MS" to "gameplay.regen.minIntervalMs",
            "AMBONMUD_GAMEPLAY_REGEN_MS_PER_CONSTITUTION" to "gameplay.regen.msPerConstitution",
            "AMBONMUD_GAMEPLAY_REGEN_AMOUNT" to "gameplay.regen.regenAmount",
            "AMBONMUD_GAMEPLAY_REGEN_MAX_PLAYERS_PER_TICK" to "gameplay.regen.maxPlayersPerTick",
        )
}
