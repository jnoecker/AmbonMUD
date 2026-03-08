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
    val ambonmud: AppConfig = AppConfig(),
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
    val admin: AdminConfig = AdminConfig(),
    val logging: LoggingConfig = LoggingConfig(),
    val database: DatabaseConfig = DatabaseConfig(),
    val redis: RedisConfig = RedisConfig(),
    val grpc: GrpcConfig = GrpcConfig(),
    val gateway: GatewayConfig = GatewayConfig(),
    val sharding: ShardingConfig = ShardingConfig(),
    val images: ImagesConfig = ImagesConfig(),
    val videos: VideosConfig = VideosConfig(),
    val audio: AudioConfig = AudioConfig(),
) {
    fun validated(): AppConfig {
        server.telnetPort.requireValidPort("ambonMUD.server.telnetPort")
        server.webPort.requireValidPort("ambonMUD.server.webPort")
        require(server.inboundChannelCapacity > 0) { "ambonMUD.server.inboundChannelCapacity must be > 0" }
        require(server.outboundChannelCapacity > 0) { "ambonMUD.server.outboundChannelCapacity must be > 0" }
        require(server.sessionOutboundQueueCapacity > 0) { "ambonMUD.server.sessionOutboundQueueCapacity must be > 0" }
        require(server.maxInboundEventsPerTick > 0) { "ambonMUD.server.maxInboundEventsPerTick must be > 0" }
        require(server.tickMillis > 0L) { "ambonMUD.server.tickMillis must be > 0" }
        require(server.inboundBudgetMs > 0L) { "ambonMUD.server.inboundBudgetMs must be > 0" }
        require(server.inboundBudgetMs < server.tickMillis) { "ambonMUD.server.inboundBudgetMs must be < tickMillis" }

        require(world.resources.all { it.isNotBlank() }) { "ambonMUD.world.resources entries must be non-blank" }
        world.startRoom?.let { sr ->
            require(sr.contains(':')) { "ambonMUD.world.startRoom must be in 'zone:room' format, got '$sr'" }
        }

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
        require(login.maxConcurrentLogins > 0) { "ambonMUD.login.maxConcurrentLogins must be > 0" }
        require(login.authThreads > 0) { "ambonMUD.login.authThreads must be > 0" }

        require(engine.mob.minActionDelayMillis >= 0L) { "ambonMUD.engine.mob.minActionDelayMillis must be >= 0" }
        require(engine.mob.maxActionDelayMillis >= engine.mob.minActionDelayMillis) {
            "ambonMUD.engine.mob.maxActionDelayMillis must be >= minActionDelayMillis"
        }
        require(engine.mob.maxActionDelayMillis - engine.mob.minActionDelayMillis <= Int.MAX_VALUE.toLong()) {
            "ambonMUD.engine.mob action delay range (max - min) must not exceed Int.MAX_VALUE ms"
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
        require(engine.regen.maxPlayersPerTick > 0) { "ambonMUD.engine.regen.maxPlayersPerTick must be > 0" }
        require(engine.regen.baseIntervalMillis > 0L) { "ambonMUD.engine.regen.baseIntervalMillis must be > 0" }
        require(engine.regen.minIntervalMillis > 0L) { "ambonMUD.engine.regen.minIntervalMillis must be > 0" }
        require(engine.regen.regenAmount > 0) { "ambonMUD.engine.regen.regenAmount must be > 0" }

        require(engine.equipment.slots.isNotEmpty()) { "ambonMUD.engine.equipment.slots must not be empty" }
        for ((id, _) in engine.equipment.slots) {
            require(id == id.trim().lowercase()) {
                "ambonMUD.engine.equipment.slots key '$id' must be lowercase with no surrounding whitespace"
            }
        }

        require(engine.scheduler.maxActionsPerTick > 0) { "ambonMUD.engine.scheduler.maxActionsPerTick must be > 0" }

        require(engine.group.maxSize in 2..20) { "ambonMUD.engine.group.maxSize must be in 2..20" }
        require(engine.group.inviteTimeoutMs > 0L) { "ambonMUD.engine.group.inviteTimeoutMs must be > 0" }
        require(engine.group.xpBonusPerMember >= 0.0) { "ambonMUD.engine.group.xpBonusPerMember must be >= 0" }

        require(engine.economy.buyMultiplier > 0.0) { "ambonMUD.engine.economy.buyMultiplier must be > 0" }
        require(engine.economy.sellMultiplier > 0.0) { "ambonMUD.engine.economy.sellMultiplier must be > 0" }

        require(engine.crafting.maxSkillLevel >= 1) { "ambonMUD.engine.crafting.maxSkillLevel must be >= 1" }
        require(engine.crafting.baseXpPerLevel > 0L) { "ambonMUD.engine.crafting.baseXpPerLevel must be > 0" }
        require(engine.crafting.xpExponent > 0.0) { "ambonMUD.engine.crafting.xpExponent must be > 0" }
        require(engine.crafting.gatherCooldownMs >= 0L) { "ambonMUD.engine.crafting.gatherCooldownMs must be >= 0" }
        require(engine.crafting.stationBonusQuantity >= 0) { "ambonMUD.engine.crafting.stationBonusQuantity must be >= 0" }

        require(engine.regen.mana.baseIntervalMillis > 0L) { "ambonMUD.engine.regen.mana.baseIntervalMillis must be > 0" }
        require(engine.regen.mana.minIntervalMillis > 0L) { "ambonMUD.engine.regen.mana.minIntervalMillis must be > 0" }
        require(engine.regen.mana.regenAmount > 0) { "ambonMUD.engine.regen.mana.regenAmount must be > 0" }

        require(engine.characterCreation.startingGold >= 0L) {
            "ambonMUD.engine.characterCreation.startingGold must be >= 0"
        }

        engine.classes.definitions.forEach { (key, def) ->
            require(def.threatMultiplier >= 0.0) {
                "ambonMUD.engine.classes.definitions.$key.threatMultiplier must be >= 0"
            }
        }

        engine.stats.definitions.forEach { (key, def) ->
            require(def.baseStat >= 0) { "ambonMUD.engine.stats.definitions.$key.baseStat must be >= 0" }
        }

        val statIds = engine.stats.definitions.keys.map { it.uppercase() }.toSet()
        val b = engine.stats.bindings
        listOf(
            b.meleeDamageStat to "meleeDamageStat",
            b.dodgeStat to "dodgeStat",
            b.spellDamageStat to "spellDamageStat",
            b.hpScalingStat to "hpScalingStat",
            b.manaScalingStat to "manaScalingStat",
            b.hpRegenStat to "hpRegenStat",
            b.manaRegenStat to "manaRegenStat",
            b.xpBonusStat to "xpBonusStat",
        ).forEach { (statId, bindingName) ->
            require(statId.uppercase() in statIds) {
                "ambonMUD.engine.stats.bindings.$bindingName references unknown stat '${statId.uppercase()}'"
            }
        }
        require(b.meleeDamageDivisor > 0) { "ambonMUD.engine.stats.bindings.meleeDamageDivisor must be > 0" }
        require(b.dodgePerPoint >= 0) { "ambonMUD.engine.stats.bindings.dodgePerPoint must be >= 0" }
        require(b.maxDodgePercent in 0..100) { "ambonMUD.engine.stats.bindings.maxDodgePercent must be in 0..100" }
        require(b.spellDamageDivisor > 0) { "ambonMUD.engine.stats.bindings.spellDamageDivisor must be > 0" }
        require(b.hpScalingDivisor > 0) { "ambonMUD.engine.stats.bindings.hpScalingDivisor must be > 0" }
        require(b.manaScalingDivisor > 0) { "ambonMUD.engine.stats.bindings.manaScalingDivisor must be > 0" }
        require(b.hpRegenMsPerPoint >= 0L) { "ambonMUD.engine.stats.bindings.hpRegenMsPerPoint must be >= 0" }
        require(b.manaRegenMsPerPoint >= 0L) { "ambonMUD.engine.stats.bindings.manaRegenMsPerPoint must be >= 0" }
        require(b.xpBonusPerPoint >= 0.0) { "ambonMUD.engine.stats.bindings.xpBonusPerPoint must be >= 0" }

        engine.abilities.definitions.forEach { (key, def) ->
            require(def.displayName.isNotBlank()) { "ability '$key' displayName must be non-blank" }
            require(def.manaCost >= 0) { "ability '$key' manaCost must be >= 0" }
            require(def.cooldownMs >= 0L) { "ability '$key' cooldownMs must be >= 0" }
            require(def.levelRequired >= 1) { "ability '$key' levelRequired must be >= 1" }
            require(def.targetType.uppercase() in listOf("ENEMY", "SELF", "ALLY")) {
                "ability '$key' targetType must be ENEMY, SELF, or ALLY, got '${def.targetType}'"
            }
            val validEffectTypes = listOf("DIRECT_DAMAGE", "DIRECT_HEAL", "APPLY_STATUS", "AREA_DAMAGE", "TAUNT")
            require(def.effect.type.uppercase() in validEffectTypes) {
                "ability '$key' effect.type must be one of $validEffectTypes, got '${def.effect.type}'"
            }
            if (def.effect.type.uppercase() == "APPLY_STATUS") {
                require(def.effect.statusEffectId.isNotBlank()) {
                    "ability '$key' effect.statusEffectId must be non-blank for APPLY_STATUS"
                }
                require(engine.statusEffects.definitions.containsKey(def.effect.statusEffectId)) {
                    "ability '$key' references unknown statusEffectId '${def.effect.statusEffectId}'"
                }
            }
        }

        engine.statusEffects.definitions.forEach { (key, def) ->
            require(def.displayName.isNotBlank()) { "statusEffect '$key' displayName must be non-blank" }
            require(
                def.effectType.uppercase() in
                    listOf("DOT", "HOT", "STAT_BUFF", "STAT_DEBUFF", "STUN", "ROOT", "SHIELD"),
            ) {
                "statusEffect '$key' effectType must be DOT, HOT, STAT_BUFF, STAT_DEBUFF, STUN, ROOT, or SHIELD"
            }
            require(def.durationMs > 0L) { "statusEffect '$key' durationMs must be > 0" }
            require(def.tickIntervalMs >= 0L) { "statusEffect '$key' tickIntervalMs must be >= 0" }
            require(def.maxStacks >= 1) { "statusEffect '$key' maxStacks must be >= 1" }
        }

        require(progression.maxLevel > 0) { "ambonMUD.progression.maxLevel must be > 0" }
        require(progression.xp.baseXp > 0L) { "ambonMUD.progression.xp.baseXp must be > 0" }
        require(progression.xp.exponent > 0.0) { "ambonMUD.progression.xp.exponent must be > 0" }
        require(progression.xp.linearXp >= 0L) { "ambonMUD.progression.xp.linearXp must be >= 0" }
        require(progression.xp.multiplier >= 0.0) { "ambonMUD.progression.xp.multiplier must be >= 0" }
        require(progression.xp.defaultKillXp >= 0L) { "ambonMUD.progression.xp.defaultKillXp must be >= 0" }
        require(progression.rewards.hpPerLevel >= 0) { "ambonMUD.progression.rewards.hpPerLevel must be >= 0" }
        require(progression.rewards.manaPerLevel >= 0) { "ambonMUD.progression.rewards.manaPerLevel must be >= 0" }
        require(progression.rewards.baseHp >= 1) { "ambonMUD.progression.rewards.baseHp must be >= 1" }
        require(progression.rewards.baseMana >= 0) { "ambonMUD.progression.rewards.baseMana must be >= 0" }

        require(transport.telnet.maxLineLen > 0) { "ambonMUD.transport.telnet.maxLineLen must be > 0" }
        require(transport.telnet.maxNonPrintablePerLine >= 0) {
            "ambonMUD.transport.telnet.maxNonPrintablePerLine must be >= 0"
        }
        require(transport.telnet.socketBacklog > 0) { "ambonMUD.transport.telnet.socketBacklog must be > 0" }
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
        observability.metricsHttpPort.requireValidPort("ambonMUD.observability.metricsHttpPort")

        if (admin.enabled) {
            admin.port.requireValidPort("ambonMUD.admin.port")
            require(admin.token.isNotBlank()) { "ambonMUD.admin.token must be non-blank when admin.enabled=true" }
        }

        if (redis.enabled) {
            require(redis.uri.isNotBlank()) { "ambonMUD.redis.uri must be non-blank when redis.enabled=true" }
            require(redis.cacheTtlSeconds > 0L) { "ambonMUD.redis.cacheTtlSeconds must be > 0" }
            if (redis.bus.enabled) {
                require(redis.bus.inboundChannel.isNotBlank()) {
                    "ambonMUD.redis.bus.inboundChannel must be non-blank when redis.bus.enabled=true"
                }
                require(redis.bus.outboundChannel.isNotBlank()) {
                    "ambonMUD.redis.bus.outboundChannel must be non-blank when redis.bus.enabled=true"
                }
                require(redis.bus.sharedSecret.isNotBlank()) {
                    "ambonMUD.redis.bus.sharedSecret must be non-blank when redis.bus.enabled=true"
                }
            }
        }

        if (mode == DeploymentMode.ENGINE || mode == DeploymentMode.GATEWAY) {
            grpc.server.port.requireValidPort("ambonMUD.grpc.server.port")
        }

        if (mode == DeploymentMode.GATEWAY) {
            require(grpc.client.engineHost.isNotBlank()) { "ambonMUD.grpc.client.engineHost must be non-blank in gateway mode" }
            grpc.client.enginePort.requireValidPort("ambonMUD.grpc.client.enginePort")
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
                entry.port.requireValidPort("ambonMUD.gateway.engines[$idx].port")
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
                port.requireValidPort("ambonMUD.sharding.advertisePort")
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
                assignment.port.requireValidPort("ambonMUD.sharding.registry.assignments[$idx].port")
                require(seenAssignmentEngineIds.add(assignment.engineId)) {
                    "ambonMUD.sharding.registry.assignments contains duplicate engineId '${assignment.engineId}'"
                }

                assignment.zones.forEach { zone ->
                    require(zone.isNotBlank()) {
                        "ambonMUD.sharding.registry.assignments[$idx].zones entries must be non-blank"
                    }
                    if (!sharding.instancing.enabled) {
                        require(seenAssignedZones.add(zone)) {
                            "Zone '$zone' is assigned more than once in ambonMUD.sharding.registry.assignments"
                        }
                    } else {
                        seenAssignedZones.add(zone)
                    }
                }
            }

            if (sharding.playerIndex.enabled) {
                require(sharding.playerIndex.heartbeatMs > 0L) {
                    "ambonMUD.sharding.playerIndex.heartbeatMs must be > 0"
                }
            }

            if (sharding.instancing.enabled) {
                require(sharding.instancing.defaultCapacity > 0) {
                    "ambonMUD.sharding.instancing.defaultCapacity must be > 0"
                }
                require(sharding.instancing.loadReportIntervalMs > 0L) {
                    "ambonMUD.sharding.instancing.loadReportIntervalMs must be > 0"
                }
                require(sharding.instancing.startZoneMinInstances >= 1) {
                    "ambonMUD.sharding.instancing.startZoneMinInstances must be >= 1"
                }
                if (sharding.instancing.autoScale.enabled) {
                    require(sharding.instancing.autoScale.evaluationIntervalMs > 0L) {
                        "ambonMUD.sharding.instancing.autoScale.evaluationIntervalMs must be > 0"
                    }
                    require(sharding.instancing.autoScale.scaleUpThreshold in 0.0..1.0) {
                        "ambonMUD.sharding.instancing.autoScale.scaleUpThreshold must be in 0.0..1.0"
                    }
                    require(sharding.instancing.autoScale.scaleDownThreshold in 0.0..1.0) {
                        "ambonMUD.sharding.instancing.autoScale.scaleDownThreshold must be in 0.0..1.0"
                    }
                    require(
                        sharding.instancing.autoScale.scaleDownThreshold <
                            sharding.instancing.autoScale.scaleUpThreshold,
                    ) {
                        "ambonMUD.sharding.instancing.autoScale.scaleDownThreshold must be < scaleUpThreshold"
                    }
                    require(sharding.instancing.autoScale.cooldownMs > 0L) {
                        "ambonMUD.sharding.instancing.autoScale.cooldownMs must be > 0"
                    }
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
    val inboundBudgetMs: Long = 30L,
)

data class WorldConfig(
    val resources: List<String> = emptyList(),
    val startRoom: String? = null,
)

data class PersistenceConfig(
    val backend: PersistenceBackend = PersistenceBackend.POSTGRES,
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
    /** Maximum number of sessions simultaneously progressing through the login/auth funnel. */
    val maxConcurrentLogins: Int = 50,
    /** Thread-pool size for BCrypt hashing, isolated from the shared Dispatchers.IO pool. */
    val authThreads: Int = Runtime.getRuntime().availableProcessors(),
)

data class EconomyConfig(
    val buyMultiplier: Double = 1.0,
    val sellMultiplier: Double = 0.5,
)

data class CraftingConfig(
    val maxSkillLevel: Int = 100,
    val baseXpPerLevel: Long = 50L,
    val xpExponent: Double = 1.5,
    val gatherCooldownMs: Long = 3000L,
    val stationBonusQuantity: Int = 1,
    val recipes: Map<String, RecipeConfigEntry> = emptyMap(),
)

data class RecipeConfigEntry(
    val displayName: String = "",
    val skill: String = "SMITHING",
    val skillRequired: Int = 1,
    val levelRequired: Int = 1,
    val materials: List<MaterialConfigEntry> = emptyList(),
    val outputItemId: String = "",
    val outputQuantity: Int = 1,
    val station: String? = null,
    val stationBonus: Int = 0,
    val xpReward: Int = 25,
)

data class MaterialConfigEntry(
    val itemId: String = "",
    val quantity: Int = 1,
)

data class CraftingSkillConfig(
    val displayName: String = "",
    val type: String = "crafting",
)

data class CraftingSkillsConfig(
    val skills: Map<String, CraftingSkillConfig> = defaultCraftingSkills(),
) {
    companion object {
        fun defaultCraftingSkills(): Map<String, CraftingSkillConfig> = linkedMapOf(
            "mining" to CraftingSkillConfig(displayName = "Mining", type = "gathering"),
            "herbalism" to CraftingSkillConfig(displayName = "Herbalism", type = "gathering"),
            "smithing" to CraftingSkillConfig(displayName = "Smithing", type = "crafting"),
            "alchemy" to CraftingSkillConfig(displayName = "Alchemy", type = "crafting"),
        )
    }
}

data class CraftingStationTypeConfig(
    val displayName: String = "",
)

data class CraftingStationTypesConfig(
    val stationTypes: Map<String, CraftingStationTypeConfig> = defaultStationTypes(),
) {
    companion object {
        fun defaultStationTypes(): Map<String, CraftingStationTypeConfig> = linkedMapOf(
            "forge" to CraftingStationTypeConfig(displayName = "Forge"),
            "alchemy_table" to CraftingStationTypeConfig(displayName = "Alchemy Table"),
            "workbench" to CraftingStationTypeConfig(displayName = "Workbench"),
        )
    }
}

data class CharacterCreationConfig(
    val startingGold: Long = 0L,
)

data class EquipmentSlotConfig(
    val displayName: String = "",
    val order: Int = 0,
)

data class EquipmentConfig(
    val slots: Map<String, EquipmentSlotConfig> = defaultEquipmentSlots(),
) {
    companion object {
        fun defaultEquipmentSlots(): Map<String, EquipmentSlotConfig> = linkedMapOf(
            "head" to EquipmentSlotConfig(displayName = "Head", order = 0),
            "body" to EquipmentSlotConfig(displayName = "Body", order = 1),
            "hand" to EquipmentSlotConfig(displayName = "Hand", order = 2),
        )
    }
}

data class GenderConfig(
    val displayName: String = "",
    val spriteCode: String = "",
)

data class GendersConfig(
    val genders: Map<String, GenderConfig> = defaultGenders(),
) {
    companion object {
        fun defaultGenders(): Map<String, GenderConfig> = linkedMapOf(
            "male" to GenderConfig(displayName = "Male", spriteCode = "male"),
            "female" to GenderConfig(displayName = "Female", spriteCode = "female"),
            "enby" to GenderConfig(displayName = "Enby", spriteCode = "enby"),
        )
    }
}

data class AchievementCategoryConfig(
    val displayName: String = "",
)

data class AchievementCategoriesConfig(
    val categories: Map<String, AchievementCategoryConfig> = defaultAchievementCategories(),
) {
    companion object {
        fun defaultAchievementCategories(): Map<String, AchievementCategoryConfig> = linkedMapOf(
            "combat" to AchievementCategoryConfig(displayName = "Combat"),
            "exploration" to AchievementCategoryConfig(displayName = "Exploration"),
            "social" to AchievementCategoryConfig(displayName = "Social"),
            "crafting" to AchievementCategoryConfig(displayName = "Crafting"),
            "class" to AchievementCategoryConfig(displayName = "Class"),
        )
    }
}

data class QuestObjectiveTypeConfig(
    val displayName: String = "",
)

data class QuestObjectiveTypesConfig(
    val types: Map<String, QuestObjectiveTypeConfig> = defaultObjectiveTypes(),
) {
    companion object {
        fun defaultObjectiveTypes(): Map<String, QuestObjectiveTypeConfig> = linkedMapOf(
            "kill" to QuestObjectiveTypeConfig(displayName = "Kill"),
            "collect" to QuestObjectiveTypeConfig(displayName = "Collect"),
        )
    }
}

data class QuestCompletionTypeConfig(
    val displayName: String = "",
)

data class QuestCompletionTypesConfig(
    val types: Map<String, QuestCompletionTypeConfig> = defaultCompletionTypes(),
) {
    companion object {
        fun defaultCompletionTypes(): Map<String, QuestCompletionTypeConfig> = linkedMapOf(
            "auto" to QuestCompletionTypeConfig(displayName = "Automatic"),
            "npc_turn_in" to QuestCompletionTypeConfig(displayName = "NPC Turn-In"),
        )
    }
}

data class AchievementCriterionTypeConfig(
    val displayName: String = "",
    val progressFormat: String = "{current}/{required}",
)

data class AchievementCriterionTypesConfig(
    val types: Map<String, AchievementCriterionTypeConfig> = defaultCriterionTypes(),
) {
    companion object {
        fun defaultCriterionTypes(): Map<String, AchievementCriterionTypeConfig> = linkedMapOf(
            "kill" to AchievementCriterionTypeConfig(displayName = "Kill", progressFormat = "{current}/{required}"),
            "reach_level" to AchievementCriterionTypeConfig(displayName = "Reach Level", progressFormat = "level {current}/{required}"),
            "quest_complete" to AchievementCriterionTypeConfig(displayName = "Quest Complete", progressFormat = "{current}/{required}"),
        )
    }
}

data class GuildRankConfig(
    val displayName: String = "",
    val level: Int = 0,
    val permissions: List<String> = emptyList(),
)

data class GuildRanksConfig(
    val ranks: Map<String, GuildRankConfig> = defaultGuildRanks(),
    /** Rank assigned to the guild founder on creation. */
    val founderRank: String = "leader",
    /** Rank assigned to new members who accept an invite. */
    val defaultRank: String = "member",
) {
    /** Returns true if the given rank has the specified permission. */
    fun hasPermission(rank: String, permission: String): Boolean =
        ranks[rank]?.permissions?.contains(permission) == true

    /** Returns the display name for a rank, falling back to the raw rank string. */
    fun displayName(rank: String): String =
        ranks[rank]?.displayName ?: rank.replaceFirstChar { it.uppercase() }

    /** Returns the rank level (higher = more authority). Used for ordering and outrank checks. */
    fun rankLevel(rank: String): Int = ranks[rank]?.level ?: 0

    /** Returns true if [actorRank] has strictly higher level than [targetRank]. */
    fun outranks(actorRank: String, targetRank: String): Boolean =
        rankLevel(actorRank) > rankLevel(targetRank)

    /** Returns the next rank above the given rank, or null if already at the top. */
    fun nextRankAbove(rank: String): String? {
        val currentLevel = rankLevel(rank)
        return ranks.entries
            .filter { it.value.level > currentLevel }
            .minByOrNull { it.value.level }
            ?.key
    }

    /** Returns the next rank below the given rank, or null if already at the bottom. */
    fun nextRankBelow(rank: String): String? {
        val currentLevel = rankLevel(rank)
        return ranks.entries
            .filter { it.value.level < currentLevel }
            .maxByOrNull { it.value.level }
            ?.key
    }

    /** Returns the rank with the highest level (the founder/leader rank). */
    fun highestRank(): String = ranks.maxByOrNull { it.value.level }?.key ?: founderRank

    companion object {
        fun defaultGuildRanks(): Map<String, GuildRankConfig> = linkedMapOf(
            "leader" to GuildRankConfig(
                displayName = "Leader",
                level = 100,
                permissions = listOf("invite", "kick", "promote", "demote", "disband", "set_motd"),
            ),
            "officer" to GuildRankConfig(
                displayName = "Officer",
                level = 50,
                permissions = listOf("invite", "kick"),
            ),
            "member" to GuildRankConfig(
                displayName = "Member",
                level = 0,
                permissions = emptyList(),
            ),
        )
    }
}

data class EffectTypeConfig(
    val displayName: String = "",
    /** Whether this effect ticks damage on the target each interval. */
    val ticksDamage: Boolean = false,
    /** Whether this effect ticks healing on the target each interval. */
    val ticksHealing: Boolean = false,
    /** Whether this effect modifies stat values while active. */
    val modifiesStats: Boolean = false,
    /** Whether this effect absorbs incoming damage via a shield pool. */
    val absorbsDamage: Boolean = false,
)

data class EffectTypesConfig(
    val types: Map<String, EffectTypeConfig> = defaultEffectTypes(),
) {
    fun get(typeId: String): EffectTypeConfig? = types[typeId]

    companion object {
        fun defaultEffectTypes(): Map<String, EffectTypeConfig> = linkedMapOf(
            "dot" to EffectTypeConfig(displayName = "Damage Over Time", ticksDamage = true),
            "hot" to EffectTypeConfig(displayName = "Heal Over Time", ticksHealing = true),
            "stat_buff" to EffectTypeConfig(displayName = "Stat Buff", modifiesStats = true),
            "stat_debuff" to EffectTypeConfig(displayName = "Stat Debuff", modifiesStats = true),
            "stun" to EffectTypeConfig(displayName = "Stun"),
            "root" to EffectTypeConfig(displayName = "Root"),
            "shield" to EffectTypeConfig(displayName = "Shield", absorbsDamage = true),
        )
    }
}

data class TargetTypeConfig(
    val displayName: String = "",
)

data class TargetTypesConfig(
    val types: Map<String, TargetTypeConfig> = defaultTargetTypes(),
) {
    companion object {
        fun defaultTargetTypes(): Map<String, TargetTypeConfig> = linkedMapOf(
            "enemy" to TargetTypeConfig(displayName = "Enemy"),
            "self" to TargetTypeConfig(displayName = "Self"),
            "ally" to TargetTypeConfig(displayName = "Ally"),
        )
    }
}

data class StackBehaviorConfig(
    val displayName: String = "",
)

data class StackBehaviorsConfig(
    val behaviors: Map<String, StackBehaviorConfig> = defaultStackBehaviors(),
) {
    companion object {
        fun defaultStackBehaviors(): Map<String, StackBehaviorConfig> = linkedMapOf(
            "refresh" to StackBehaviorConfig(displayName = "Refresh"),
            "stack" to StackBehaviorConfig(displayName = "Stack"),
            "none" to StackBehaviorConfig(displayName = "None"),
        )
    }
}

data class EngineConfig(
    val mob: MobEngineConfig = MobEngineConfig(),
    val combat: CombatEngineConfig = CombatEngineConfig(),
    val regen: RegenEngineConfig = RegenEngineConfig(),
    val scheduler: SchedulerEngineConfig = SchedulerEngineConfig(),
    val abilities: AbilityEngineConfig = AbilityEngineConfig(),
    val statusEffects: StatusEffectEngineConfig = StatusEffectEngineConfig(),
    val economy: EconomyConfig = EconomyConfig(),
    val group: GroupConfig = GroupConfig(),
    val guild: GuildConfig = GuildConfig(),
    val crafting: CraftingConfig = CraftingConfig(),
    val friends: FriendsConfig = FriendsConfig(),
    val debug: EngineDebugConfig = EngineDebugConfig(),
    val classes: ClassEngineConfig = ClassEngineConfig(),
    val races: RaceEngineConfig = RaceEngineConfig(),
    val stats: StatsEngineConfig = StatsEngineConfig(),
    val equipment: EquipmentConfig = EquipmentConfig(),
    val genders: GendersConfig = GendersConfig(),
    val achievementCategories: AchievementCategoriesConfig = AchievementCategoriesConfig(),
    val craftingSkills: CraftingSkillsConfig = CraftingSkillsConfig(),
    val craftingStationTypes: CraftingStationTypesConfig = CraftingStationTypesConfig(),
    val questObjectiveTypes: QuestObjectiveTypesConfig = QuestObjectiveTypesConfig(),
    val questCompletionTypes: QuestCompletionTypesConfig = QuestCompletionTypesConfig(),
    val effectTypes: EffectTypesConfig = EffectTypesConfig(),
    val targetTypes: TargetTypesConfig = TargetTypesConfig(),
    val stackBehaviors: StackBehaviorsConfig = StackBehaviorsConfig(),
    val achievementCriterionTypes: AchievementCriterionTypesConfig = AchievementCriterionTypesConfig(),
    val navigation: NavigationConfig = NavigationConfig(),
    val guildRanks: GuildRanksConfig = GuildRanksConfig(),
    val characterCreation: CharacterCreationConfig = CharacterCreationConfig(),
    val commands: CommandsConfig = CommandsConfig(),
    /** Maps class name (e.g. "WARRIOR") to a fully-qualified RoomId string for new-character placement. */
    val classStartRooms: Map<String, String> = emptyMap(),
)

data class NavigationConfig(
    val recall: RecallConfig = RecallConfig(),
)

data class RecallConfig(
    /** Cooldown between recall uses in milliseconds (default 5 minutes). */
    val cooldownMs: Long = 300_000L,
    val messages: RecallMessagesConfig = RecallMessagesConfig(),
)

data class RecallMessagesConfig(
    val combatBlocked: String = "You are fighting for your life and cannot recall!",
    val cooldownRemaining: String = "You need to rest before recalling again. ({seconds} seconds remaining)",
    val castBegin: String = "You close your eyes and whisper a prayer...",
    val unreachable: String = "Your recall point is unreachable.",
    val departNotice: String = "vanishes in a flash of light.",
    val arriveNotice: String = "appears in a flash of light.",
    val arrival: String = "You feel a familiar warmth and find yourself back at your recall point.",
)

data class CommandMetadata(
    val usage: String = "",
    val category: String = "general",
    val staff: Boolean = false,
)

data class CommandsConfig(
    val entries: Map<String, CommandMetadata> = defaultCommandEntries(),
) {
    fun generateHelp(isStaff: Boolean): String = buildString {
        val grouped = entries.entries
            .filter { !it.value.staff }
            .groupBy { it.value.category }

        val orderedCategories = listOf(
            "navigation",
            "communication",
            "items",
            "combat",
            "progression",
            "shops",
            "quests",
            "groups",
            "guilds",
            "crafting",
            "world",
            "social",
            "utility",
        )

        appendLine("Commands:")
        for (category in orderedCategories) {
            val cmds = grouped[category] ?: continue
            for ((_, meta) in cmds) {
                appendLine("    ${meta.usage}")
            }
        }

        if (isStaff) {
            val staffCmds = entries.entries.filter { it.value.staff }
            if (staffCmds.isNotEmpty()) {
                appendLine("Staff commands (requires staff flag):")
                for ((_, meta) in staffCmds) {
                    appendLine("    ${meta.usage}")
                }
            }
        }
    }.trimEnd()

    companion object {
        @Suppress("LongMethod")
        fun defaultCommandEntries(): Map<String, CommandMetadata> = linkedMapOf(
            "help" to CommandMetadata(usage = "help/?", category = "utility"),
            "look" to CommandMetadata(usage = "look/l (or look <direction>)", category = "navigation"),
            "move" to CommandMetadata(usage = "n/s/e/w/u/d", category = "navigation"),
            "exits" to CommandMetadata(usage = "exits/ex", category = "navigation"),
            "recall" to CommandMetadata(usage = "recall", category = "navigation"),
            "say" to CommandMetadata(usage = "say <msg> or '<msg>", category = "communication"),
            "emote" to CommandMetadata(usage = "emote <msg>", category = "communication"),
            "pose" to CommandMetadata(usage = "pose <msg>", category = "communication"),
            "who" to CommandMetadata(usage = "who", category = "communication"),
            "tell" to CommandMetadata(usage = "tell/t <player> <msg>", category = "communication"),
            "whisper" to CommandMetadata(usage = "whisper/wh <player> <msg>", category = "communication"),
            "gossip" to CommandMetadata(usage = "gossip/gs <msg>", category = "communication"),
            "shout" to CommandMetadata(usage = "shout/sh <msg>", category = "communication"),
            "ooc" to CommandMetadata(usage = "ooc <msg>", category = "communication"),
            "inventory" to CommandMetadata(usage = "inventory/inv/i", category = "items"),
            "equipment" to CommandMetadata(usage = "equipment/eq", category = "items"),
            "wear" to CommandMetadata(usage = "wear/equip <item>", category = "items"),
            "remove" to CommandMetadata(usage = "remove/unequip <slot>", category = "items"),
            "get" to CommandMetadata(usage = "get/take/pickup <item>", category = "items"),
            "drop" to CommandMetadata(usage = "drop <item>", category = "items"),
            "use" to CommandMetadata(usage = "use <item>", category = "items"),
            "give" to CommandMetadata(usage = "give <item> <player>", category = "items"),
            "talk" to CommandMetadata(usage = "talk <npc>", category = "social"),
            "kill" to CommandMetadata(usage = "kill <mob>", category = "combat"),
            "flee" to CommandMetadata(usage = "flee", category = "combat"),
            "cast" to CommandMetadata(usage = "cast/c <spell> [target]", category = "combat"),
            "spells" to CommandMetadata(usage = "spells/abilities/skills", category = "progression"),
            "effects" to CommandMetadata(usage = "effects/buffs/debuffs", category = "progression"),
            "score" to CommandMetadata(usage = "score/sc", category = "progression"),
            "balance" to CommandMetadata(usage = "gold/balance", category = "shops"),
            "shop_list" to CommandMetadata(usage = "list/shop", category = "shops"),
            "buy" to CommandMetadata(usage = "buy <item>", category = "shops"),
            "sell" to CommandMetadata(usage = "sell <item>", category = "shops"),
            "quest_log" to CommandMetadata(usage = "quest log/list", category = "quests"),
            "quest_info" to CommandMetadata(usage = "quest info <name>", category = "quests"),
            "quest_abandon" to CommandMetadata(usage = "quest abandon <name>", category = "quests"),
            "accept" to CommandMetadata(usage = "accept <quest>", category = "quests"),
            "achievements" to CommandMetadata(usage = "achievements/ach", category = "quests"),
            "group_invite" to CommandMetadata(usage = "group invite <player>", category = "groups"),
            "group_accept" to CommandMetadata(usage = "group accept", category = "groups"),
            "group_leave" to CommandMetadata(usage = "group leave", category = "groups"),
            "group_kick" to CommandMetadata(usage = "group kick <player>", category = "groups"),
            "group_list" to CommandMetadata(usage = "group list (or just 'group')", category = "groups"),
            "gtell" to CommandMetadata(usage = "gtell/gt <message>", category = "groups"),
            "guild_create" to CommandMetadata(usage = "guild create <name> <tag>", category = "guilds"),
            "guild_disband" to CommandMetadata(usage = "guild disband", category = "guilds"),
            "guild_invite" to CommandMetadata(usage = "guild invite <player>", category = "guilds"),
            "guild_accept" to CommandMetadata(usage = "guild accept", category = "guilds"),
            "guild_leave" to CommandMetadata(usage = "guild leave", category = "guilds"),
            "guild_kick" to CommandMetadata(usage = "guild kick <player>", category = "guilds"),
            "guild_promote" to CommandMetadata(usage = "guild promote <player>", category = "guilds"),
            "guild_demote" to CommandMetadata(usage = "guild demote <player>", category = "guilds"),
            "guild_motd" to CommandMetadata(usage = "guild motd <message>", category = "guilds"),
            "guild_roster" to CommandMetadata(usage = "guild roster", category = "guilds"),
            "guild_info" to CommandMetadata(usage = "guild info (or just 'guild')", category = "guilds"),
            "gchat" to CommandMetadata(usage = "gchat/g <message>", category = "guilds"),
            "gather" to CommandMetadata(usage = "gather/harvest/mine <node>", category = "crafting"),
            "craft" to CommandMetadata(usage = "craft/make <recipe>", category = "crafting"),
            "recipes" to CommandMetadata(usage = "recipes [filter]", category = "crafting"),
            "craftskills" to CommandMetadata(usage = "craftskills/professions", category = "crafting"),
            "open" to CommandMetadata(usage = "open <door|container>", category = "world"),
            "close" to CommandMetadata(usage = "close <door|container>", category = "world"),
            "unlock" to CommandMetadata(usage = "unlock <door|container>", category = "world"),
            "lock" to CommandMetadata(usage = "lock <door|container>", category = "world"),
            "search" to CommandMetadata(usage = "search <container>", category = "world"),
            "get_from" to CommandMetadata(usage = "get <item> from <container>", category = "world"),
            "put_in" to CommandMetadata(usage = "put <item> <container>", category = "world"),
            "pull" to CommandMetadata(usage = "pull <lever>", category = "world"),
            "read" to CommandMetadata(usage = "read <sign>", category = "world"),
            "title" to CommandMetadata(usage = "title <titleName> | title clear", category = "progression"),
            "gender" to CommandMetadata(usage = "gender <option>", category = "progression"),
            "friend" to CommandMetadata(usage = "friend list | add <player> | remove <player>", category = "social"),
            "mail" to CommandMetadata(usage = "mail list | read <n> | send <player> | delete <n>", category = "social"),
            "ansi" to CommandMetadata(usage = "ansi on/off", category = "utility"),
            "colors" to CommandMetadata(usage = "colors", category = "utility"),
            "clear" to CommandMetadata(usage = "clear", category = "utility"),
            "quit" to CommandMetadata(usage = "quit/exit", category = "utility"),
            "phase" to CommandMetadata(usage = "phase/layer [instance]", category = "utility"),
            // Staff commands
            "goto" to CommandMetadata(usage = "goto <zone:room | room | zone:>", category = "admin", staff = true),
            "transfer" to CommandMetadata(usage = "transfer <player> <room>", category = "admin", staff = true),
            "spawn" to CommandMetadata(usage = "spawn <mob-template>", category = "admin", staff = true),
            "smite" to CommandMetadata(usage = "smite <player|mob>", category = "admin", staff = true),
            "staff_kick" to CommandMetadata(usage = "kick <player>", category = "admin", staff = true),
            "dispel" to CommandMetadata(usage = "dispel <player|mob>", category = "admin", staff = true),
            "setlevel" to CommandMetadata(usage = "setlevel <player> <level>", category = "admin", staff = true),
            "shutdown" to CommandMetadata(usage = "shutdown", category = "admin", staff = true),
        )
    }
}

data class EngineDebugConfig(
    val enableSwarmClass: Boolean = false,
)

data class ClassDefinitionConfig(
    val displayName: String = "",
    val hpPerLevel: Int = 4,
    val manaPerLevel: Int = 8,
    val description: String = "",
    val selectable: Boolean = true,
    val primaryStat: String = "",
    val startRoom: String = "",
    val threatMultiplier: Double = 1.0,
)

data class ClassEngineConfig(
    val definitions: Map<String, ClassDefinitionConfig> = defaultClassDefinitions(),
) {
    companion object {
        fun defaultClassDefinitions(): Map<String, ClassDefinitionConfig> = mapOf(
            "WARRIOR" to ClassDefinitionConfig(
                displayName = "Warrior",
                hpPerLevel = 8,
                manaPerLevel = 4,
                primaryStat = "STR",
                threatMultiplier = 1.5,
            ),
            "MAGE" to ClassDefinitionConfig(
                displayName = "Mage",
                hpPerLevel = 4,
                manaPerLevel = 16,
                primaryStat = "INT",
            ),
            "CLERIC" to ClassDefinitionConfig(
                displayName = "Cleric",
                hpPerLevel = 6,
                manaPerLevel = 12,
                primaryStat = "WIS",
            ),
            "ROGUE" to ClassDefinitionConfig(
                displayName = "Rogue",
                hpPerLevel = 5,
                manaPerLevel = 8,
                primaryStat = "DEX",
            ),
            "SWARM" to ClassDefinitionConfig(
                displayName = "Swarm",
                hpPerLevel = 2,
                manaPerLevel = 3,
                selectable = false,
            ),
        )
    }
}

data class RaceStatModsConfig(
    val str: Int = 0,
    val dex: Int = 0,
    val con: Int = 0,
    val int: Int = 0,
    val wis: Int = 0,
    val cha: Int = 0,
)

data class RaceDefinitionConfig(
    val displayName: String = "",
    val description: String = "",
    val statMods: RaceStatModsConfig = RaceStatModsConfig(),
)

data class RaceEngineConfig(
    val definitions: Map<String, RaceDefinitionConfig> = defaultRaceDefinitions(),
) {
    companion object {
        fun defaultRaceDefinitions(): Map<String, RaceDefinitionConfig> = mapOf(
            "HUMAN" to RaceDefinitionConfig(
                displayName = "Human",
                statMods = RaceStatModsConfig(str = 1, cha = 1),
            ),
            "ELF" to RaceDefinitionConfig(
                displayName = "Elf",
                statMods = RaceStatModsConfig(str = -1, dex = 2, con = -2, int = 1),
            ),
            "DWARF" to RaceDefinitionConfig(
                displayName = "Dwarf",
                statMods = RaceStatModsConfig(str = 1, dex = -1, con = 2, wis = 1, cha = -2),
            ),
            "HALFLING" to RaceDefinitionConfig(
                displayName = "Halfling",
                statMods = RaceStatModsConfig(str = -2, dex = 2, con = -1, wis = 1, cha = 1),
            ),
        )
    }
}

data class StatDefinitionConfig(
    val displayName: String = "",
    val abbreviation: String = "",
    val description: String = "",
    val baseStat: Int = 10,
)

data class StatBindingsConfig(
    val meleeDamageStat: String = "STR",
    val meleeDamageDivisor: Int = 3,
    val dodgeStat: String = "DEX",
    val dodgePerPoint: Int = 2,
    val maxDodgePercent: Int = 30,
    val spellDamageStat: String = "INT",
    val spellDamageDivisor: Int = 3,
    val hpScalingStat: String = "CON",
    val hpScalingDivisor: Int = 5,
    val manaScalingStat: String = "INT",
    val manaScalingDivisor: Int = 5,
    val hpRegenStat: String = "CON",
    val hpRegenMsPerPoint: Long = 200L,
    val manaRegenStat: String = "WIS",
    val manaRegenMsPerPoint: Long = 200L,
    val xpBonusStat: String = "CHA",
    val xpBonusPerPoint: Double = 0.005,
)

data class StatsEngineConfig(
    val definitions: Map<String, StatDefinitionConfig> = defaultStatDefinitions(),
    val bindings: StatBindingsConfig = StatBindingsConfig(),
) {
    companion object {
        fun defaultStatDefinitions(): Map<String, StatDefinitionConfig> = linkedMapOf(
            "STR" to StatDefinitionConfig(
                displayName = "Strength",
                abbreviation = "STR",
                description = "Physical power. Increases melee damage.",
                baseStat = 10,
            ),
            "DEX" to StatDefinitionConfig(
                displayName = "Dexterity",
                abbreviation = "DEX",
                description = "Agility and reflexes. Increases dodge chance.",
                baseStat = 10,
            ),
            "CON" to StatDefinitionConfig(
                displayName = "Constitution",
                abbreviation = "CON",
                description = "Endurance and health. Increases max HP and HP regen.",
                baseStat = 10,
            ),
            "INT" to StatDefinitionConfig(
                displayName = "Intelligence",
                abbreviation = "INT",
                description = "Arcane aptitude. Increases max mana and spell damage.",
                baseStat = 10,
            ),
            "WIS" to StatDefinitionConfig(
                displayName = "Wisdom",
                abbreviation = "WIS",
                description = "Insight and perception. Increases mana regen.",
                baseStat = 10,
            ),
            "CHA" to StatDefinitionConfig(
                displayName = "Charisma",
                abbreviation = "CHA",
                description = "Force of personality. Increases XP gain.",
                baseStat = 10,
            ),
        )
    }
}

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
    val baseHp: Int = 10,
    val baseMana: Int = 20,
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
    val baseGoldMin: Long = 0L,
    val baseGoldMax: Long = 0L,
    val goldPerLevel: Long = 0L,
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
            baseGoldMin = 1L,
            baseGoldMax = 3L,
            goldPerLevel = 1L,
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
            baseGoldMin = 2L,
            baseGoldMax = 8L,
            goldPerLevel = 2L,
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
            baseGoldMin = 10L,
            baseGoldMax = 25L,
            goldPerLevel = 5L,
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
            baseGoldMin = 50L,
            baseGoldMax = 100L,
            goldPerLevel = 15L,
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
    val minActionDelayMillis: Long = 8_000L,
    val maxActionDelayMillis: Long = 20_000L,
    val tiers: MobTiersConfig = MobTiersConfig(),
)

data class CombatEngineConfig(
    val maxCombatsPerTick: Int = 20,
    val tickMillis: Long = 2_000L,
    val minDamage: Int = 1,
    val maxDamage: Int = 4,
    val feedback: CombatFeedbackConfig = CombatFeedbackConfig(),
)

data class CombatFeedbackConfig(
    val enabled: Boolean = false,
    val roomBroadcastEnabled: Boolean = false,
)

data class RegenEngineConfig(
    val maxPlayersPerTick: Int = 50,
    val baseIntervalMillis: Long = 5_000L,
    val minIntervalMillis: Long = 1_000L,
    val regenAmount: Int = 1,
    val mana: ManaRegenConfig = ManaRegenConfig(),
)

data class ManaRegenConfig(
    val baseIntervalMillis: Long = 3_000L,
    val minIntervalMillis: Long = 1_000L,
    val regenAmount: Int = 1,
)

data class SchedulerEngineConfig(
    val maxActionsPerTick: Int = 100,
)

data class GroupConfig(
    val maxSize: Int = 5,
    val inviteTimeoutMs: Long = 60_000L,
    val xpBonusPerMember: Double = 0.10,
)

data class GuildConfig(
    val maxSize: Int = 50,
    val inviteTimeoutMs: Long = 60_000L,
)

data class FriendsConfig(
    val maxFriends: Int = 50,
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
    val requiredClass: String = "",
    val image: String = "",
)

data class AbilityEffectConfig(
    val type: String = "DIRECT_DAMAGE",
    val minDamage: Int = 0,
    val maxDamage: Int = 0,
    val minHeal: Int = 0,
    val maxHeal: Int = 0,
    val statusEffectId: String = "",
    val flatThreat: Double = 50.0,
    val margin: Double = 10.0,
)

data class StatusEffectEngineConfig(
    val definitions: Map<String, StatusEffectDefinitionConfig> = emptyMap(),
)

data class StatusEffectDefinitionConfig(
    val displayName: String = "",
    val effectType: String = "DOT",
    val durationMs: Long = 5000L,
    val tickIntervalMs: Long = 0L,
    val tickMinValue: Int = 0,
    val tickMaxValue: Int = 0,
    val shieldAmount: Int = 0,
    val stackBehavior: String = "REFRESH",
    val maxStacks: Int = 1,
    val strMod: Int = 0,
    val dexMod: Int = 0,
    val conMod: Int = 0,
    val intMod: Int = 0,
    val wisMod: Int = 0,
    val chaMod: Int = 0,
)

data class TransportConfig(
    val telnet: TelnetTransportConfig = TelnetTransportConfig(),
    val websocket: WebSocketTransportConfig = WebSocketTransportConfig(),
    val maxInboundBackpressureFailures: Int = 3,
)

data class TelnetTransportConfig(
    val maxLineLen: Int = 1024,
    val maxNonPrintablePerLine: Int = 32,
    /** OS-level TCP accept backlog for the telnet ServerSocket (default 256 vs JVM default of 50). */
    val socketBacklog: Int = 256,
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

data class AdminConfig(
    /** Enable the admin HTTP dashboard. Requires a non-blank [token]. */
    val enabled: Boolean = false,
    /** Port the admin dashboard listens on. */
    val port: Int = 9091,
    /** Bearer/Basic-auth password required for every admin request. */
    val token: String = "",
    /** Optional Grafana dashboard URL shown as a link on the overview page. */
    val grafanaUrl: String = "",
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
    /**
     * Start zone for instance-aware session routing.
     * When set alongside `sharding.instancing.enabled`, new sessions are routed to
     * the least-loaded instance of this zone instead of round-robin.
     */
    val startZone: String = "",
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
    val sharedSecret: String = "",
)

data class RedisConfig(
    val enabled: Boolean = true,
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

/** Zone instancing (layering) settings. */
data class InstanceConfig(
    /** Enable zone instancing. When true, multiple engines may host copies of the same zone. */
    val enabled: Boolean = false,
    /** Default per-instance player capacity. */
    val defaultCapacity: Int = 200,
    /** How often (ms) engines report their per-zone player counts to the registry. */
    val loadReportIntervalMs: Long = 5_000L,
    /** Minimum number of instances to maintain for the start zone. */
    val startZoneMinInstances: Int = 1,
    /** Auto-scaling settings. */
    val autoScale: AutoScaleConfig = AutoScaleConfig(),
)

/** Auto-scaling signal configuration for zone instances. */
data class AutoScaleConfig(
    /** Enable auto-scale evaluation. Produces signals; does not manage processes. */
    val enabled: Boolean = false,
    /** How often (ms) to evaluate scaling decisions. */
    val evaluationIntervalMs: Long = 30_000L,
    /** Fraction of total capacity above which a scale-up is signalled. */
    val scaleUpThreshold: Double = 0.8,
    /** Fraction of total capacity below which a scale-down is signalled. */
    val scaleDownThreshold: Double = 0.2,
    /** Cooldown (ms) between scaling decisions for the same zone. */
    val cooldownMs: Long = 60_000L,
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
    /** Zone instancing (layering) settings. */
    val instancing: InstanceConfig = InstanceConfig(),
)

data class ImagesConfig(
    val baseUrl: String = "/images/",
    val globalAssets: Map<String, String> = DEFAULT_GLOBAL_ASSETS,
    /** Level thresholds for player sprite tiers, checked highest-first. */
    val spriteLevelTiers: List<Int> = listOf(50, 40, 30, 20, 10, 1),
    /** Sprite tier used for staff players regardless of level. */
    val staffSpriteTier: Int = 60,
) {
    companion object {
        val DEFAULT_GLOBAL_ASSETS: Map<String, String> = linkedMapOf(
            "compass_rose" to "global_assets/compass_rose.png",
            "direction_marker" to "global_assets/direction_marker.png",
            "stairs_up" to "global_assets/stairs_up.png",
            "stairs_down" to "global_assets/stairs_down.png",
            "video_available_indicator" to "global_assets/video_available_indicator.png",
            "shop_kiosk" to "global_assets/shop_kiosk.png",
            "dialog_indicator" to "global_assets/dialog_indicator.png",
            "aggro_indicator" to "global_assets/aggro_indicator.png",
            "quest_available_indicator" to "global_assets/quest_available_indicator.png",
            "quest_complete_indicator" to "global_assets/quest_complete_indicator.png",
            "minimap_unexplored" to "global_assets/minimap-unexplored.png",
            "map_background" to "global_assets/map_background.png",
        )
    }
}

data class VideosConfig(
    val baseUrl: String = "/videos/",
)

data class AudioConfig(
    val baseUrl: String = "/audio/",
)

private fun Int.requireValidPort(fieldName: String) {
    require(this in 1..65535) { "$fieldName must be between 1 and 65535" }
}

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
    require(tier.baseGoldMin >= 0L) { "ambonMUD.engine.mob.tiers.$name.baseGoldMin must be >= 0" }
    require(tier.baseGoldMax >= tier.baseGoldMin) {
        "ambonMUD.engine.mob.tiers.$name.baseGoldMax must be >= baseGoldMin"
    }
    require(tier.goldPerLevel >= 0L) { "ambonMUD.engine.mob.tiers.$name.goldPerLevel must be >= 0" }
}
