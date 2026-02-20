package dev.ambon.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.readText

object AmbonMudConfigLoader {
    private const val DEPLOYMENT_FILE_NAME = "ambonmud.yml"
    private const val GAMEPLAY_FILE_NAME = "ambonmud.gameplay.yml"
    private const val LEGACY_DEMO_AUTO_LAUNCH_PROP = "quickmud.demo.autolaunchBrowser"

    private val mapper: ObjectMapper =
        ObjectMapper(YAMLFactory())
            .registerModule(KotlinModule.Builder().build())

    fun load(
        baseDir: Path = Paths.get("").toAbsolutePath(),
        env: Map<String, String> = System.getenv(),
        systemProperties: Map<String, String> = currentSystemProperties(),
    ): AmbonMudConfig {
        val mergedFiles =
            listOf(baseDir.resolve(DEPLOYMENT_FILE_NAME), baseDir.resolve(GAMEPLAY_FILE_NAME))
                .fold(RawAmbonMudConfig()) { acc, path ->
                    if (!path.exists()) return@fold acc
                    acc.merge(readConfigFile(path))
                }

        var config = mergedFiles.resolve()
        config = applyOverrides(config, envOverrides(env), "environment variable")
        config = applyOverrides(config, propertyOverrides(systemProperties), "system property")
        validate(config)
        return config
    }

    private fun readConfigFile(path: Path): RawAmbonMudConfig {
        val text =
            try {
                path.readText()
            } catch (e: Exception) {
                throw AmbonMudConfigException("Failed to read config file '$path': ${e.message}", e)
            }

        return try {
            mapper.readValue(text)
        } catch (e: Exception) {
            throw AmbonMudConfigException("Failed to parse config file '$path': ${e.message}", e)
        }
    }

    private fun envOverrides(env: Map<String, String>): Map<String, String> =
        ENV_KEY_TO_CONFIG_KEY
            .mapNotNull { (envKey, configKey) -> env[envKey]?.let { configKey to it } }
            .toMap()

    private fun propertyOverrides(systemProperties: Map<String, String>): Map<String, String> {
        val mapped =
            systemProperties.entries
                .filter { it.key.startsWith("ambonmud.") }
                .associate { (key, value) -> key.removePrefix("ambonmud.") to value }
                .toMutableMap()

        if (!mapped.containsKey("deployment.demoAutoLaunchBrowser")) {
            val legacy = systemProperties[LEGACY_DEMO_AUTO_LAUNCH_PROP]
            if (legacy != null) {
                mapped["deployment.demoAutoLaunchBrowser"] = legacy
            }
        }

        return mapped
    }

    private fun applyOverrides(
        config: AmbonMudConfig,
        overrides: Map<String, String>,
        sourceName: String,
    ): AmbonMudConfig {
        var out = config
        for ((key, rawValue) in overrides) {
            out = applySingleOverride(out, key, rawValue, sourceName)
        }
        return out
    }

    private fun applySingleOverride(
        config: AmbonMudConfig,
        key: String,
        rawValue: String,
        sourceName: String,
    ): AmbonMudConfig =
        when (key) {
            "deployment.telnetPort" ->
                config.withDeployment { it.copy(telnetPort = parseInt(rawValue, key, sourceName)) }
            "deployment.webPort" ->
                config.withDeployment {
                    val parsedPort = parseInt(rawValue, key, sourceName)
                    val previousDefaultUrl = "http://localhost:${it.webPort}"
                    val nextUrl =
                        if (it.webClientUrl == previousDefaultUrl) {
                            "http://localhost:$parsedPort"
                        } else {
                            it.webClientUrl
                        }
                    it.copy(webPort = parsedPort, webClientUrl = nextUrl)
                }
            "deployment.webHost" ->
                config.withDeployment { it.copy(webHost = rawValue) }
            "deployment.webClientUrl" ->
                config.withDeployment { it.copy(webClientUrl = rawValue) }
            "deployment.playerDataDir" ->
                config.withDeployment { it.copy(playerDataDir = rawValue) }
            "deployment.worldResources" ->
                config.withDeployment { it.copy(worldResources = parseStringList(rawValue, key, sourceName)) }
            "deployment.inboundChannelCapacity" ->
                config.withDeployment { it.copy(inboundChannelCapacity = parseInt(rawValue, key, sourceName)) }
            "deployment.outboundChannelCapacity" ->
                config.withDeployment { it.copy(outboundChannelCapacity = parseInt(rawValue, key, sourceName)) }
            "deployment.sessionOutboundQueueCapacity" ->
                config.withDeployment { it.copy(sessionOutboundQueueCapacity = parseInt(rawValue, key, sourceName)) }
            "deployment.telnetLineMaxLength" ->
                config.withDeployment { it.copy(telnetLineMaxLength = parseInt(rawValue, key, sourceName)) }
            "deployment.telnetMaxNonPrintablePerLine" ->
                config.withDeployment { it.copy(telnetMaxNonPrintablePerLine = parseInt(rawValue, key, sourceName)) }
            "deployment.telnetReadBufferBytes" ->
                config.withDeployment { it.copy(telnetReadBufferBytes = parseInt(rawValue, key, sourceName)) }
            "deployment.promptText" ->
                config.withDeployment { it.copy(promptText = rawValue) }
            "deployment.webStopGracePeriodMillis" ->
                config.withDeployment { it.copy(webStopGracePeriodMillis = parseLong(rawValue, key, sourceName)) }
            "deployment.webStopTimeoutMillis" ->
                config.withDeployment { it.copy(webStopTimeoutMillis = parseLong(rawValue, key, sourceName)) }
            "deployment.webMaxCloseReasonLength" ->
                config.withDeployment { it.copy(webMaxCloseReasonLength = parseInt(rawValue, key, sourceName)) }
            "deployment.demoAutoLaunchBrowser" ->
                config.withDeployment { it.copy(demoAutoLaunchBrowser = parseBoolean(rawValue, key, sourceName)) }
            "gameplay.engineTickMillis" ->
                config.withGameplay { it.copy(engineTickMillis = parseLong(rawValue, key, sourceName)) }
            "gameplay.schedulerMaxActionsPerTick" ->
                config.withGameplay { it.copy(schedulerMaxActionsPerTick = parseInt(rawValue, key, sourceName)) }
            "gameplay.loginMaxWrongPasswordRetries" ->
                config.withGameplay { it.copy(loginMaxWrongPasswordRetries = parseInt(rawValue, key, sourceName)) }
            "gameplay.loginMaxFailedLoginAttemptsBeforeDisconnect" ->
                config.withGameplay {
                    it.copy(loginMaxFailedLoginAttemptsBeforeDisconnect = parseInt(rawValue, key, sourceName))
                }
            "gameplay.mob.minWanderDelayMillis" ->
                config.withGameplay { gameplay ->
                    gameplay.copy(mob = gameplay.mob.copy(minWanderDelayMillis = parseLong(rawValue, key, sourceName)))
                }
            "gameplay.mob.maxWanderDelayMillis" ->
                config.withGameplay { gameplay ->
                    gameplay.copy(mob = gameplay.mob.copy(maxWanderDelayMillis = parseLong(rawValue, key, sourceName)))
                }
            "gameplay.mob.maxMovesPerTick" ->
                config.withGameplay { gameplay ->
                    gameplay.copy(mob = gameplay.mob.copy(maxMovesPerTick = parseInt(rawValue, key, sourceName)))
                }
            "gameplay.combat.tickMillis" ->
                config.withGameplay { gameplay ->
                    gameplay.copy(combat = gameplay.combat.copy(tickMillis = parseLong(rawValue, key, sourceName)))
                }
            "gameplay.combat.minDamage" ->
                config.withGameplay { gameplay ->
                    gameplay.copy(combat = gameplay.combat.copy(minDamage = parseInt(rawValue, key, sourceName)))
                }
            "gameplay.combat.maxDamage" ->
                config.withGameplay { gameplay ->
                    gameplay.copy(combat = gameplay.combat.copy(maxDamage = parseInt(rawValue, key, sourceName)))
                }
            "gameplay.combat.maxCombatsPerTick" ->
                config.withGameplay { gameplay ->
                    gameplay.copy(combat = gameplay.combat.copy(maxCombatsPerTick = parseInt(rawValue, key, sourceName)))
                }
            "gameplay.regen.baseIntervalMs" ->
                config.withGameplay { gameplay ->
                    gameplay.copy(regen = gameplay.regen.copy(baseIntervalMs = parseLong(rawValue, key, sourceName)))
                }
            "gameplay.regen.minIntervalMs" ->
                config.withGameplay { gameplay ->
                    gameplay.copy(regen = gameplay.regen.copy(minIntervalMs = parseLong(rawValue, key, sourceName)))
                }
            "gameplay.regen.msPerConstitution" ->
                config.withGameplay { gameplay ->
                    gameplay.copy(regen = gameplay.regen.copy(msPerConstitution = parseLong(rawValue, key, sourceName)))
                }
            "gameplay.regen.regenAmount" ->
                config.withGameplay { gameplay ->
                    gameplay.copy(regen = gameplay.regen.copy(regenAmount = parseInt(rawValue, key, sourceName)))
                }
            "gameplay.regen.maxPlayersPerTick" ->
                config.withGameplay { gameplay ->
                    gameplay.copy(regen = gameplay.regen.copy(maxPlayersPerTick = parseInt(rawValue, key, sourceName)))
                }
            else -> config
        }

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

    private fun RawAmbonMudConfig.resolve(): AmbonMudConfig {
        val defaults = AmbonMudConfig()

        val deploymentRaw = deployment
        val webPort = deploymentRaw?.webPort ?: defaults.deployment.webPort

        val deploymentConfig =
            DeploymentConfig(
                telnetPort = deploymentRaw?.telnetPort ?: defaults.deployment.telnetPort,
                webPort = webPort,
                webHost = deploymentRaw?.webHost ?: defaults.deployment.webHost,
                webClientUrl = deploymentRaw?.webClientUrl ?: "http://localhost:$webPort",
                playerDataDir = deploymentRaw?.playerDataDir ?: defaults.deployment.playerDataDir,
                worldResources = deploymentRaw?.worldResources ?: defaults.deployment.worldResources,
                inboundChannelCapacity = deploymentRaw?.inboundChannelCapacity ?: defaults.deployment.inboundChannelCapacity,
                outboundChannelCapacity = deploymentRaw?.outboundChannelCapacity ?: defaults.deployment.outboundChannelCapacity,
                sessionOutboundQueueCapacity =
                    deploymentRaw?.sessionOutboundQueueCapacity ?: defaults.deployment.sessionOutboundQueueCapacity,
                telnetLineMaxLength = deploymentRaw?.telnetLineMaxLength ?: defaults.deployment.telnetLineMaxLength,
                telnetMaxNonPrintablePerLine =
                    deploymentRaw?.telnetMaxNonPrintablePerLine ?: defaults.deployment.telnetMaxNonPrintablePerLine,
                telnetReadBufferBytes = deploymentRaw?.telnetReadBufferBytes ?: defaults.deployment.telnetReadBufferBytes,
                promptText = deploymentRaw?.promptText ?: defaults.deployment.promptText,
                webStopGracePeriodMillis = deploymentRaw?.webStopGracePeriodMillis ?: defaults.deployment.webStopGracePeriodMillis,
                webStopTimeoutMillis = deploymentRaw?.webStopTimeoutMillis ?: defaults.deployment.webStopTimeoutMillis,
                webMaxCloseReasonLength = deploymentRaw?.webMaxCloseReasonLength ?: defaults.deployment.webMaxCloseReasonLength,
                demoAutoLaunchBrowser = deploymentRaw?.demoAutoLaunchBrowser ?: defaults.deployment.demoAutoLaunchBrowser,
            )

        val gameplayRaw = gameplay
        val mobRaw = gameplayRaw?.mob
        val combatRaw = gameplayRaw?.combat
        val regenRaw = gameplayRaw?.regen

        val gameplayConfig =
            GameplayConfig(
                engineTickMillis = gameplayRaw?.engineTickMillis ?: defaults.gameplay.engineTickMillis,
                schedulerMaxActionsPerTick = gameplayRaw?.schedulerMaxActionsPerTick ?: defaults.gameplay.schedulerMaxActionsPerTick,
                loginMaxWrongPasswordRetries =
                    gameplayRaw?.loginMaxWrongPasswordRetries ?: defaults.gameplay.loginMaxWrongPasswordRetries,
                loginMaxFailedLoginAttemptsBeforeDisconnect =
                    gameplayRaw?.loginMaxFailedLoginAttemptsBeforeDisconnect
                        ?: defaults.gameplay.loginMaxFailedLoginAttemptsBeforeDisconnect,
                mob =
                    MobGameplayConfig(
                        minWanderDelayMillis = mobRaw?.minWanderDelayMillis ?: defaults.gameplay.mob.minWanderDelayMillis,
                        maxWanderDelayMillis = mobRaw?.maxWanderDelayMillis ?: defaults.gameplay.mob.maxWanderDelayMillis,
                        maxMovesPerTick = mobRaw?.maxMovesPerTick ?: defaults.gameplay.mob.maxMovesPerTick,
                    ),
                combat =
                    CombatGameplayConfig(
                        tickMillis = combatRaw?.tickMillis ?: defaults.gameplay.combat.tickMillis,
                        minDamage = combatRaw?.minDamage ?: defaults.gameplay.combat.minDamage,
                        maxDamage = combatRaw?.maxDamage ?: defaults.gameplay.combat.maxDamage,
                        maxCombatsPerTick = combatRaw?.maxCombatsPerTick ?: defaults.gameplay.combat.maxCombatsPerTick,
                    ),
                regen =
                    RegenGameplayConfig(
                        baseIntervalMs = regenRaw?.baseIntervalMs ?: defaults.gameplay.regen.baseIntervalMs,
                        minIntervalMs = regenRaw?.minIntervalMs ?: defaults.gameplay.regen.minIntervalMs,
                        msPerConstitution = regenRaw?.msPerConstitution ?: defaults.gameplay.regen.msPerConstitution,
                        regenAmount = regenRaw?.regenAmount ?: defaults.gameplay.regen.regenAmount,
                        maxPlayersPerTick = regenRaw?.maxPlayersPerTick ?: defaults.gameplay.regen.maxPlayersPerTick,
                    ),
            )

        return AmbonMudConfig(deployment = deploymentConfig, gameplay = gameplayConfig)
    }

    private fun RawAmbonMudConfig.merge(other: RawAmbonMudConfig): RawAmbonMudConfig =
        RawAmbonMudConfig(
            deployment = mergeNullable(deployment, other.deployment) { base, overlay -> base.merge(overlay) },
            gameplay = mergeNullable(gameplay, other.gameplay) { base, overlay -> base.merge(overlay) },
        )

    private fun RawDeploymentConfig.merge(other: RawDeploymentConfig): RawDeploymentConfig =
        RawDeploymentConfig(
            telnetPort = other.telnetPort ?: telnetPort,
            webPort = other.webPort ?: webPort,
            webHost = other.webHost ?: webHost,
            webClientUrl = other.webClientUrl ?: webClientUrl,
            playerDataDir = other.playerDataDir ?: playerDataDir,
            worldResources = other.worldResources ?: worldResources,
            inboundChannelCapacity = other.inboundChannelCapacity ?: inboundChannelCapacity,
            outboundChannelCapacity = other.outboundChannelCapacity ?: outboundChannelCapacity,
            sessionOutboundQueueCapacity = other.sessionOutboundQueueCapacity ?: sessionOutboundQueueCapacity,
            telnetLineMaxLength = other.telnetLineMaxLength ?: telnetLineMaxLength,
            telnetMaxNonPrintablePerLine = other.telnetMaxNonPrintablePerLine ?: telnetMaxNonPrintablePerLine,
            telnetReadBufferBytes = other.telnetReadBufferBytes ?: telnetReadBufferBytes,
            promptText = other.promptText ?: promptText,
            webStopGracePeriodMillis = other.webStopGracePeriodMillis ?: webStopGracePeriodMillis,
            webStopTimeoutMillis = other.webStopTimeoutMillis ?: webStopTimeoutMillis,
            webMaxCloseReasonLength = other.webMaxCloseReasonLength ?: webMaxCloseReasonLength,
            demoAutoLaunchBrowser = other.demoAutoLaunchBrowser ?: demoAutoLaunchBrowser,
        )

    private fun RawGameplayConfig.merge(other: RawGameplayConfig): RawGameplayConfig =
        RawGameplayConfig(
            engineTickMillis = other.engineTickMillis ?: engineTickMillis,
            schedulerMaxActionsPerTick = other.schedulerMaxActionsPerTick ?: schedulerMaxActionsPerTick,
            loginMaxWrongPasswordRetries = other.loginMaxWrongPasswordRetries ?: loginMaxWrongPasswordRetries,
            loginMaxFailedLoginAttemptsBeforeDisconnect =
                other.loginMaxFailedLoginAttemptsBeforeDisconnect ?: loginMaxFailedLoginAttemptsBeforeDisconnect,
            mob = mergeNullable(mob, other.mob) { base, overlay -> base.merge(overlay) },
            combat = mergeNullable(combat, other.combat) { base, overlay -> base.merge(overlay) },
            regen = mergeNullable(regen, other.regen) { base, overlay -> base.merge(overlay) },
        )

    private fun RawMobGameplayConfig.merge(other: RawMobGameplayConfig): RawMobGameplayConfig =
        RawMobGameplayConfig(
            minWanderDelayMillis = other.minWanderDelayMillis ?: minWanderDelayMillis,
            maxWanderDelayMillis = other.maxWanderDelayMillis ?: maxWanderDelayMillis,
            maxMovesPerTick = other.maxMovesPerTick ?: maxMovesPerTick,
        )

    private fun RawCombatGameplayConfig.merge(other: RawCombatGameplayConfig): RawCombatGameplayConfig =
        RawCombatGameplayConfig(
            tickMillis = other.tickMillis ?: tickMillis,
            minDamage = other.minDamage ?: minDamage,
            maxDamage = other.maxDamage ?: maxDamage,
            maxCombatsPerTick = other.maxCombatsPerTick ?: maxCombatsPerTick,
        )

    private fun RawRegenGameplayConfig.merge(other: RawRegenGameplayConfig): RawRegenGameplayConfig =
        RawRegenGameplayConfig(
            baseIntervalMs = other.baseIntervalMs ?: baseIntervalMs,
            minIntervalMs = other.minIntervalMs ?: minIntervalMs,
            msPerConstitution = other.msPerConstitution ?: msPerConstitution,
            regenAmount = other.regenAmount ?: regenAmount,
            maxPlayersPerTick = other.maxPlayersPerTick ?: maxPlayersPerTick,
        )

    private fun <T> mergeNullable(
        base: T?,
        overlay: T?,
        merge: (T, T) -> T,
    ): T? =
        when {
            base == null -> overlay
            overlay == null -> base
            else -> merge(base, overlay)
        }

    private fun ensure(
        condition: Boolean,
        message: String,
    ) {
        if (!condition) {
            throw AmbonMudConfigException(message)
        }
    }

    private fun parseInt(
        raw: String,
        key: String,
        sourceName: String,
    ): Int =
        raw.trim().toIntOrNull()
            ?: throw AmbonMudConfigException("Invalid integer for $sourceName '$key': '$raw'")

    private fun parseLong(
        raw: String,
        key: String,
        sourceName: String,
    ): Long =
        raw.trim().toLongOrNull()
            ?: throw AmbonMudConfigException("Invalid long for $sourceName '$key': '$raw'")

    private fun parseBoolean(
        raw: String,
        key: String,
        sourceName: String,
    ): Boolean =
        when (raw.trim().lowercase()) {
            "true" -> true
            "false" -> false
            else -> throw AmbonMudConfigException("Invalid boolean for $sourceName '$key': '$raw'")
        }

    private fun parseStringList(
        raw: String,
        key: String,
        sourceName: String,
    ): List<String> {
        val values =
            raw
                .split(',', ';')
                .map { it.trim() }
                .filter { it.isNotEmpty() }

        if (values.isEmpty()) {
            throw AmbonMudConfigException("Invalid list for $sourceName '$key': '$raw'")
        }

        return values
    }

    private inline fun AmbonMudConfig.withDeployment(transform: (DeploymentConfig) -> DeploymentConfig): AmbonMudConfig =
        copy(deployment = transform(deployment))

    private inline fun AmbonMudConfig.withGameplay(transform: (GameplayConfig) -> GameplayConfig): AmbonMudConfig =
        copy(gameplay = transform(gameplay))

    private fun currentSystemProperties(): Map<String, String> {
        val properties = System.getProperties()
        return properties.stringPropertyNames().associateWith { key -> properties.getProperty(key) }
    }

    private data class RawAmbonMudConfig(
        val deployment: RawDeploymentConfig? = null,
        val gameplay: RawGameplayConfig? = null,
    )

    private data class RawDeploymentConfig(
        val telnetPort: Int? = null,
        val webPort: Int? = null,
        val webHost: String? = null,
        val webClientUrl: String? = null,
        val playerDataDir: String? = null,
        val worldResources: List<String>? = null,
        val inboundChannelCapacity: Int? = null,
        val outboundChannelCapacity: Int? = null,
        val sessionOutboundQueueCapacity: Int? = null,
        val telnetLineMaxLength: Int? = null,
        val telnetMaxNonPrintablePerLine: Int? = null,
        val telnetReadBufferBytes: Int? = null,
        val promptText: String? = null,
        val webStopGracePeriodMillis: Long? = null,
        val webStopTimeoutMillis: Long? = null,
        val webMaxCloseReasonLength: Int? = null,
        val demoAutoLaunchBrowser: Boolean? = null,
    )

    private data class RawGameplayConfig(
        val engineTickMillis: Long? = null,
        val schedulerMaxActionsPerTick: Int? = null,
        val loginMaxWrongPasswordRetries: Int? = null,
        val loginMaxFailedLoginAttemptsBeforeDisconnect: Int? = null,
        val mob: RawMobGameplayConfig? = null,
        val combat: RawCombatGameplayConfig? = null,
        val regen: RawRegenGameplayConfig? = null,
    )

    private data class RawMobGameplayConfig(
        val minWanderDelayMillis: Long? = null,
        val maxWanderDelayMillis: Long? = null,
        val maxMovesPerTick: Int? = null,
    )

    private data class RawCombatGameplayConfig(
        val tickMillis: Long? = null,
        val minDamage: Int? = null,
        val maxDamage: Int? = null,
        val maxCombatsPerTick: Int? = null,
    )

    private data class RawRegenGameplayConfig(
        val baseIntervalMs: Long? = null,
        val minIntervalMs: Long? = null,
        val msPerConstitution: Long? = null,
        val regenAmount: Int? = null,
        val maxPlayersPerTick: Int? = null,
    )

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
