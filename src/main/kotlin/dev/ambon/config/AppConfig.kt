package dev.ambon.config

import dev.ambon.domain.world.Direction
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/** Maximum allowed value for per-session outbound queue capacity to prevent OOM from misconfiguration. */
private const val MAX_SESSION_OUTBOUND_QUEUE_CAPACITY = 100_000

/**
 * Soft upper bound on multiplicative scaling rates. Mirrors Arcanum's
 * `validateConfig` `[1.0, 2.0]` range — values above this are valid math but
 * indicate a likely typo (a rate of 2.0 already produces ~500M× growth over
 * 30 levels). Rates above this trigger a warning, not an error.
 */
private const val MAX_SCALING_RATE = 2.0

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
    val voices: VoicesConfig = VoicesConfig(),
) {
    private fun warnConfig(message: String) {
        logger.warn { "CONFIG WARNING: $message" }
    }

    fun validated(): AppConfig {
        validateServer()
        validateWorld()
        validatePersistence()
        validateLogin()
        validateEngine()
        validateProgression()
        validateTransport()
        validateDemo()
        validateObservability()
        validateAdmin()
        validateRedis()
        validateGrpc()
        validateGateway()
        validateSharding()
        validateCrossCuttingDependencies()
        validateProductionMode()
        return this
    }

    private fun validateServer() {
        server.telnetPort.requireValidPort("ambonMUD.server.telnetPort")
        server.webPort.requireValidPort("ambonMUD.server.webPort")
        server.inboundChannelCapacity.requirePositive("ambonMUD.server.inboundChannelCapacity")
        server.outboundChannelCapacity.requirePositive("ambonMUD.server.outboundChannelCapacity")
        require(server.sessionOutboundQueueCapacity in 1..MAX_SESSION_OUTBOUND_QUEUE_CAPACITY) {
            "ambonMUD.server.sessionOutboundQueueCapacity must be in 1..$MAX_SESSION_OUTBOUND_QUEUE_CAPACITY, got ${server.sessionOutboundQueueCapacity}"
        }
        server.maxInboundEventsPerTick.requirePositive("ambonMUD.server.maxInboundEventsPerTick")
        server.tickMillis.requirePositive("ambonMUD.server.tickMillis")
        server.inboundBudgetMs.requirePositive("ambonMUD.server.inboundBudgetMs")
        require(server.inboundBudgetMs < server.tickMillis) { "ambonMUD.server.inboundBudgetMs must be < tickMillis" }
    }

    private fun validateWorld() {
        require(world.resources.all { it.isNotBlank() }) { "ambonMUD.world.resources entries must be non-blank" }
        require(world.startRoom != null) { "ambonMUD.world.startRoom must be configured" }
        world.startRoom.let { sr ->
            require(sr.contains(':')) { "ambonMUD.world.startRoom must be in 'zone:room' format, got '$sr'" }
        }
    }

    private fun validatePersistence() {
        require(persistence.rootDir.isNotBlank()) { "ambonMUD.persistence.rootDir must be non-blank" }
        persistence.worker.flushIntervalMs.requirePositive("ambonMUD.persistence.worker.flushIntervalMs")

        if (persistence.backend == PersistenceBackend.POSTGRES) {
            require(database.jdbcUrl.isNotBlank()) { "ambonMUD.database.jdbcUrl required when backend=POSTGRES" }
            database.maxPoolSize.requirePositive("ambonMUD.database.maxPoolSize")
        }
    }

    private fun validateLogin() {
        require(login.maxWrongPasswordRetries >= 0) { "ambonMUD.login.maxWrongPasswordRetries must be >= 0" }
        login.maxFailedAttemptsBeforeDisconnect.requirePositive("ambonMUD.login.maxFailedAttemptsBeforeDisconnect")
        login.maxConcurrentLogins.requirePositive("ambonMUD.login.maxConcurrentLogins")
        login.authThreads.requirePositive("ambonMUD.login.authThreads")
    }

    private fun validateEngine() {
        validateEngineMob()
        validateEngineCombat()
        validateEngineRegen()
        validateEngineEquipment()
        validateEngineScheduler()
        validateEngineGroup()
        validateEngineEconomy()
        validateEngineCrafting()
        validateEngineHousing()
        validateEngineCharacterCreation()
        validateEngineClasses()
        validateEngineRaces()
        validateEngineStats()
        validateEngineAbilities()
        validateEngineStatusEffects()
        validateEnginePets()
        validateEngineBank()
        validateEngineStylist()
        validateEngineAkathavae()
        validateEngineWorldTime()
        validateEngineWeather()
        validateEngineWorldEvents()
        validateEngineEnchanting()
        validateEngineFactions()
        validateEngineDailyQuests()
        validateEngineAutoQuests()
        validateEngineGlobalQuests()
        validateEngineLottery()
        validateEngineGambling()
        validateEngineJukebox()
    }

    private fun validateEngineDailyQuests() {
        val dq = engine.dailyQuests
        require(dq.resetHourUtc in 0..23) { "ambonMUD.engine.dailyQuests.resetHourUtc must be 0–23" }
        require(dq.dailySlots in 0..20) { "ambonMUD.engine.dailyQuests.dailySlots must be 0–20" }
        require(dq.weeklySlots in 0..10) { "ambonMUD.engine.dailyQuests.weeklySlots must be 0–10" }
        require(dq.streakBonusPercent >= 0) { "ambonMUD.engine.dailyQuests.streakBonusPercent must be >= 0" }
        require(dq.streakMaxDays >= 0) { "ambonMUD.engine.dailyQuests.streakMaxDays must be >= 0" }
        if (dq.enabled) {
            if (dq.dailySlots > 0) {
                require(dq.dailyPool.size >= dq.dailySlots) {
                    "ambonMUD.engine.dailyQuests.dailyPool must have at least ${dq.dailySlots} entries (dailySlots)"
                }
            }
            if (dq.weeklySlots > 0) {
                require(dq.weeklyPool.size >= dq.weeklySlots) {
                    "ambonMUD.engine.dailyQuests.weeklyPool must have at least ${dq.weeklySlots} entries (weeklySlots)"
                }
            }
        }
    }

    private fun validateEngineRaces() {
        val validKinds = dev.ambon.domain.RacialAbilityKind.entries.map { it.name }.toSet()
        engine.races.definitions.forEach { (raceId, raceDef) ->
            val ability = raceDef.racialAbility ?: return@forEach
            val prefix = "ambonMUD.engine.races.definitions.$raceId.racialAbility"
            require(ability.kind.uppercase() in validKinds) {
                "$prefix.kind must be one of $validKinds, got '${ability.kind}'"
            }
            require(ability.cooldownMs >= 0L) { "$prefix.cooldownMs must be >= 0" }
            require(ability.triggerHealthPct in 0..100) { "$prefix.triggerHealthPct must be 0..100" }
            require(ability.damageMultiplier >= 0.0) { "$prefix.damageMultiplier must be >= 0" }
            require(ability.aoeDamagePctOfMaxHp >= 0.0) { "$prefix.aoeDamagePctOfMaxHp must be >= 0" }
            require(ability.regenPctOfMaxHp >= 0.0) { "$prefix.regenPctOfMaxHp must be >= 0" }
            require(ability.petCountMin >= 1) { "$prefix.petCountMin must be >= 1" }
            require(ability.petCountMax >= ability.petCountMin) { "$prefix.petCountMax must be >= petCountMin" }
            require(ability.phaseTicks >= 1) { "$prefix.phaseTicks must be >= 1" }
            val kind = dev.ambon.domain.RacialAbilityKind.valueOf(ability.kind.uppercase())
            if (kind.trigger == dev.ambon.domain.RacialTrigger.LOW_HEALTH) {
                require(ability.triggerHealthPct in 1..100) {
                    "$prefix.triggerHealthPct must be 1..100 for low-health ability '$kind'"
                }
            }
            // Fields whose absence makes the ability silently do nothing at runtime (the system
            // bails out early when they are missing) are required up front so a misconfigured race
            // fails fast at boot rather than no-op'ing mid-fight. References must also resolve to a
            // real definition of the right shape — the most likely production slip is carrying the
            // racialAbility block into an overlay but forgetting its pet template / status effect.
            when (kind) {
                dev.ambon.domain.RacialAbilityKind.AURELIA_DAZZLE -> {
                    val statusId = ability.stunStatusId
                    require(!statusId.isNullOrBlank()) { "$prefix.stunStatusId is required for '$kind'" }
                    val def = engine.statusEffects.definitions[statusId]
                    require(def != null) {
                        "$prefix.stunStatusId '$statusId' is not a defined status effect " +
                            "(ambonMUD.engine.statusEffects.definitions)"
                    }
                    require(def.effectType == "stun") {
                        "$prefix.stunStatusId '$statusId' must have effectType 'stun' to stun enemies, " +
                            "got '${def.effectType}'"
                    }
                }
                dev.ambon.domain.RacialAbilityKind.LITHAE_STONEFORM -> {
                    // stoneStatusId is optional (the root is a nicety on top of disengage), but if
                    // set it must resolve to a real 'root' effect, else the player stays mobile.
                    val statusId = ability.stoneStatusId
                    if (!statusId.isNullOrBlank()) {
                        val def = engine.statusEffects.definitions[statusId]
                        require(def != null) {
                            "$prefix.stoneStatusId '$statusId' is not a defined status effect " +
                                "(ambonMUD.engine.statusEffects.definitions)"
                        }
                        require(def.effectType == "root") {
                            "$prefix.stoneStatusId '$statusId' must have effectType 'root' to root the player, " +
                                "got '${def.effectType}'"
                        }
                    }
                }
                dev.ambon.domain.RacialAbilityKind.MYCORAE_SPORES,
                dev.ambon.domain.RacialAbilityKind.ARCHAE_DRENGARIAE,
                -> {
                    val templateKey = ability.petTemplateKey
                    require(!templateKey.isNullOrBlank()) { "$prefix.petTemplateKey is required for '$kind'" }
                    require(engine.pets.definitions.containsKey(templateKey)) {
                        "$prefix.petTemplateKey '$templateKey' is not a defined pet template " +
                            "(ambonMUD.engine.pets.definitions)"
                    }
                }
                else -> Unit
            }
        }
    }

    private fun validateEngineMob() {
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

        require(engine.season.cycleLengthMs > 0L) { "ambonMUD.engine.season.cycleLengthMs must be > 0" }
        require(engine.mobVariants.chance in 0.0..1.0) { "ambonMUD.engine.mobVariants.chance must be in 0.0..1.0" }
        engine.mobVariants.variants.forEach { (id, v) ->
            require(v.weight >= 0.0) { "ambonMUD.engine.mobVariants.variants.$id.weight must be >= 0" }
            require(v.announce in setOf("ROOM", "ZONE", "SERVER")) {
                "ambonMUD.engine.mobVariants.variants.$id.announce must be ROOM, ZONE, or SERVER"
            }
        }
    }

    private fun validateEngineCombat() {
        engine.combat.maxCombatsPerTick.requirePositive("ambonMUD.engine.combat.maxCombatsPerTick")
        engine.combat.tickMillis.requirePositive("ambonMUD.engine.combat.tickMillis")
        require(!engine.combat.feedback.roomBroadcastEnabled || engine.combat.feedback.enabled) {
            "ambonMUD.engine.combat.feedback.roomBroadcastEnabled requires feedback.enabled=true"
        }
        validateDeath()
    }

    private fun validateDeath() {
        val d = engine.death
        require(d.respawnHpFraction in 0.05..1.0) {
            "ambonMUD.engine.death.respawnHpFraction must be in [0.05, 1.0] (got ${d.respawnHpFraction})"
        }
        require(d.respawnManaFraction in 0.0..1.0) {
            "ambonMUD.engine.death.respawnManaFraction must be in [0.0, 1.0] (got ${d.respawnManaFraction})"
        }
        require(d.xpPenaltyFraction in 0.0..0.5) {
            "ambonMUD.engine.death.xpPenaltyFraction must be in [0.0, 0.5] (got ${d.xpPenaltyFraction})"
        }
        d.sanctumRoom?.let { sr ->
            require(sr.contains(':')) {
                "ambonMUD.engine.death.sanctumRoom must be in 'zone:room' format, got '$sr'"
            }
        }
    }

    private fun validateEngineRegen() {
        engine.regen.cycleTargetMillis.requirePositive("ambonMUD.engine.regen.cycleTargetMillis")
        engine.regen.minPlayersPerTick.requirePositive("ambonMUD.engine.regen.minPlayersPerTick")
        engine.regen.maxPlayersPerTick.requirePositive("ambonMUD.engine.regen.maxPlayersPerTick")
        require(engine.regen.maxPlayersPerTick >= engine.regen.minPlayersPerTick) {
            "ambonMUD.engine.regen.maxPlayersPerTick must be >= minPlayersPerTick"
        }
        engine.regen.baseIntervalMillis.requirePositive("ambonMUD.engine.regen.baseIntervalMillis")
        engine.regen.minIntervalMillis.requirePositive("ambonMUD.engine.regen.minIntervalMillis")
        require(engine.regen.regenPercent > 0.0 && engine.regen.regenPercent <= 1.0) {
            "ambonMUD.engine.regen.regenPercent must be in (0.0, 1.0]"
        }
        require(engine.regen.inCombatMultiplier in 0.0..1.0) {
            "ambonMUD.engine.regen.inCombatMultiplier must be in [0.0, 1.0]"
        }
        require(engine.regen.innMultiplier >= 1.0) {
            "ambonMUD.engine.regen.innMultiplier must be >= 1.0"
        }
        engine.regen.mana.baseIntervalMillis.requirePositive("ambonMUD.engine.regen.mana.baseIntervalMillis")
        engine.regen.mana.minIntervalMillis.requirePositive("ambonMUD.engine.regen.mana.minIntervalMillis")
        require(engine.regen.mana.regenPercent > 0.0 && engine.regen.mana.regenPercent <= 1.0) {
            "ambonMUD.engine.regen.mana.regenPercent must be in (0.0, 1.0]"
        }
    }

    private fun validateEngineEquipment() {
        require(engine.equipment.slots.isNotEmpty()) { "ambonMUD.engine.equipment.slots must not be empty" }
        val slotOrders = mutableSetOf<Int>()
        for ((id, slot) in engine.equipment.slots) {
            require(id == id.trim().lowercase()) {
                "ambonMUD.engine.equipment.slots key '$id' must be lowercase with no surrounding whitespace"
            }
            require(slotOrders.add(slot.order)) {
                "ambonMUD.engine.equipment.slots: duplicate order ${slot.order} for slot '$id'"
            }
        }
    }

    private fun validateEngineScheduler() {
        engine.scheduler.maxActionsPerTick.requirePositive("ambonMUD.engine.scheduler.maxActionsPerTick")
    }

    private fun validateEngineGroup() {
        require(engine.group.maxSize in 2..20) { "ambonMUD.engine.group.maxSize must be in 2..20" }
        engine.group.inviteTimeoutMs.requirePositive("ambonMUD.engine.group.inviteTimeoutMs")
        require(engine.group.xpBonusPerMember >= 0.0) { "ambonMUD.engine.group.xpBonusPerMember must be >= 0" }
    }

    private fun validateEngineEconomy() {
        require(engine.economy.buyMultiplier > 0.0) { "ambonMUD.engine.economy.buyMultiplier must be > 0" }
        require(engine.economy.sellMultiplier > 0.0) { "ambonMUD.engine.economy.sellMultiplier must be > 0" }
    }

    private fun validateEngineCrafting() {
        require(engine.crafting.maxSkillLevel >= 1) { "ambonMUD.engine.crafting.maxSkillLevel must be >= 1" }
        engine.crafting.baseXpPerLevel.requirePositive("ambonMUD.engine.crafting.baseXpPerLevel")
        require(engine.crafting.xpExponent > 0.0) { "ambonMUD.engine.crafting.xpExponent must be > 0" }
        require(engine.crafting.gatherCooldownMs >= 0L) { "ambonMUD.engine.crafting.gatherCooldownMs must be >= 0" }
        require(engine.crafting.stationBonusQuantity >= 0) { "ambonMUD.engine.crafting.stationBonusQuantity must be >= 0" }
    }

    private fun validateEngineHousing() {
        if (engine.housing.enabled && engine.housing.templates.isNotEmpty()) {
            val entryTemplates = engine.housing.templates.values.count { it.isEntry }
            require(entryTemplates == 1) {
                "ambonMUD.engine.housing.templates must have exactly one entry template (isEntry=true), found $entryTemplates"
            }
            engine.housing.templates.forEach { (key, tmpl) ->
                require(tmpl.title.isNotBlank()) { "ambonMUD.engine.housing.templates.$key.title must be non-blank" }
                require(tmpl.cost >= 0L) { "ambonMUD.engine.housing.templates.$key.cost must be >= 0" }
                require(tmpl.maxDroppedItems >= 0) { "ambonMUD.engine.housing.templates.$key.maxDroppedItems must be >= 0" }
            }
        }
    }

    private fun validateEngineCharacterCreation() {
        require(engine.characterCreation.startingGold >= 0L) {
            "ambonMUD.engine.characterCreation.startingGold must be >= 0"
        }
    }

    private fun validateEngineClasses() {
        engine.classes.definitions.forEach { (key, def) ->
            if (def.threatMultiplier < 0.0) {
                warnConfig("engine.classes.definitions.$key.threatMultiplier is ${def.threatMultiplier}, expected >= 0")
            }
            require(def.hpScalingRate >= 1.0) {
                "ambonMUD.engine.classes.definitions.$key.hpScalingRate must be >= 1.0"
            }
            require(def.manaScalingRate >= 1.0) {
                "ambonMUD.engine.classes.definitions.$key.manaScalingRate must be >= 1.0"
            }
            if (def.hpScalingRate > MAX_SCALING_RATE) {
                warnConfig(
                    "engine.classes.definitions.$key.hpScalingRate is ${def.hpScalingRate}, " +
                        "expected <= $MAX_SCALING_RATE (rates above ~2x/level produce runaway growth)",
                )
            }
            if (def.manaScalingRate > MAX_SCALING_RATE) {
                warnConfig(
                    "engine.classes.definitions.$key.manaScalingRate is ${def.manaScalingRate}, " +
                        "expected <= $MAX_SCALING_RATE (rates above ~2x/level produce runaway growth)",
                )
            }
        }
    }

    private fun validateEngineStats() {
        engine.stats.definitions.forEach { (key, def) ->
            if (def.baseStat < 0) warnConfig("engine.stats.definitions.$key.baseStat is ${def.baseStat}, expected >= 0")
        }

        val statIds = engine.stats.definitions.keys.map { it.uppercase() }.toSet()
        val b = engine.stats.bindings
        listOf(
            b.meleeDamageStat to "meleeDamageStat",
            b.dodgeStat to "dodgeStat",
            b.spellDamageStat to "spellDamageStat",
            b.healStat to "healStat",
            b.buffStat to "buffStat",
            b.hpScalingStat to "hpScalingStat",
            b.manaScalingStat to "manaScalingStat",
            b.hpRegenStat to "hpRegenStat",
            b.manaRegenStat to "manaRegenStat",
            b.xpBonusStat to "xpBonusStat",
        ).forEach { (statId, bindingName) ->
            if (statId.uppercase() !in statIds) {
                warnConfig("engine.stats.bindings.$bindingName references unknown stat '${statId.uppercase()}'")
            }
        }
        require(b.meleeStatMultiplier >= 0.0) { "ambonMUD.engine.stats.bindings.meleeStatMultiplier must be >= 0" }
        require(b.meleeLevelScalingRate >= 1.0) {
            "ambonMUD.engine.stats.bindings.meleeLevelScalingRate must be >= 1.0 (use 1.0 to disable)"
        }
        require(b.meleeVarianceMin > 0.0 && b.meleeVarianceMax >= b.meleeVarianceMin) {
            "ambonMUD.engine.stats.bindings.meleeVarianceMin/Max must satisfy 0 < min <= max"
        }
        require(b.meleeBaseAttackPower >= 0) {
            "ambonMUD.engine.stats.bindings.meleeBaseAttackPower must be >= 0"
        }
        require(b.meleeArmorMitigationK > 0.0) {
            "ambonMUD.engine.stats.bindings.meleeArmorMitigationK must be > 0"
        }
        require(b.dodgePerPoint >= 0) { "ambonMUD.engine.stats.bindings.dodgePerPoint must be >= 0" }
        require(b.maxDodgePercent in 0..100) { "ambonMUD.engine.stats.bindings.maxDodgePercent must be in 0..100" }
        require(b.spellStatMultiplier >= 0.0) { "ambonMUD.engine.stats.bindings.spellStatMultiplier must be >= 0" }
        require(b.spellLevelScalingRate >= 1.0) {
            "ambonMUD.engine.stats.bindings.spellLevelScalingRate must be >= 1.0 (use 1.0 to disable)"
        }
        require(b.spellVarianceMin > 0.0 && b.spellVarianceMax >= b.spellVarianceMin) {
            "ambonMUD.engine.stats.bindings.spellVarianceMin/Max must satisfy 0 < min <= max"
        }
        require(b.healStatMultiplier >= 0.0) { "ambonMUD.engine.stats.bindings.healStatMultiplier must be >= 0" }
        require(b.healLevelScalingRate >= 1.0) {
            "ambonMUD.engine.stats.bindings.healLevelScalingRate must be >= 1.0 (use 1.0 to disable)"
        }
        require(b.healVarianceMin > 0.0 && b.healVarianceMax >= b.healVarianceMin) {
            "ambonMUD.engine.stats.bindings.healVarianceMin/Max must satisfy 0 < min <= max"
        }
        require(b.buffDurationPerStat >= 0.0) {
            "ambonMUD.engine.stats.bindings.buffDurationPerStat must be >= 0"
        }
        require(b.buffMagnitudePerStat >= 0.0) {
            "ambonMUD.engine.stats.bindings.buffMagnitudePerStat must be >= 0"
        }
        b.hpScalingDivisor.requirePositive("ambonMUD.engine.stats.bindings.hpScalingDivisor")
        b.manaScalingDivisor.requirePositive("ambonMUD.engine.stats.bindings.manaScalingDivisor")
        require(b.hpRegenMsPerPoint >= 0L) { "ambonMUD.engine.stats.bindings.hpRegenMsPerPoint must be >= 0" }
        require(b.manaRegenMsPerPoint >= 0L) { "ambonMUD.engine.stats.bindings.manaRegenMsPerPoint must be >= 0" }
        require(b.xpBonusPerPoint >= 0.0) { "ambonMUD.engine.stats.bindings.xpBonusPerPoint must be >= 0" }
    }

    private fun validateEngineAbilities() {
        engine.abilities.definitions.forEach { (key, def) ->
            if (def.displayName.isBlank()) warnConfig("ability '$key' displayName is blank")
            if (def.manaCostPct < 0.0) warnConfig("ability '$key' manaCostPct is ${def.manaCostPct}, expected >= 0")
            if (def.cooldownMs < 0L) warnConfig("ability '$key' cooldownMs is ${def.cooldownMs}, expected >= 0")
            if (def.levelRequired < 1) warnConfig("ability '$key' levelRequired is ${def.levelRequired}, expected >= 1")
            require(def.skillPointCost >= 0) {
                "ambonMUD.engine.abilities.definitions.$key.skillPointCost must be >= 0"
            }
            if (def.targetType.isBlank()) warnConfig("ability '$key' targetType is blank")
            if (def.effect.type.isBlank()) warnConfig("ability '$key' effect.type is blank")
            if (def.effect.type.uppercase() == "APPLY_STATUS") {
                if (def.effect.statusEffectId.isBlank()) {
                    warnConfig("ability '$key' effect.statusEffectId is blank for APPLY_STATUS")
                } else if (!engine.statusEffects.definitions.containsKey(def.effect.statusEffectId)) {
                    warnConfig("ability '$key' references unknown statusEffectId '${def.effect.statusEffectId}'")
                }
            }
        }
    }

    private fun validateEngineStatusEffects() {
        engine.statusEffects.definitions.forEach { (key, def) ->
            if (def.displayName.isBlank()) warnConfig("statusEffect '$key' displayName is blank")
            if (def.effectType.isBlank()) warnConfig("statusEffect '$key' effectType is blank")
            if (def.durationMs <= 0L) warnConfig("statusEffect '$key' durationMs is ${def.durationMs}, expected > 0")
            if (def.tickIntervalMs < 0L) warnConfig("statusEffect '$key' tickIntervalMs is ${def.tickIntervalMs}, expected >= 0")
            require(def.maxStacks >= 1) { "statusEffect '$key' maxStacks is ${def.maxStacks}, must be >= 1" }
        }
    }

    private fun validateEnginePets() {
        require(engine.pets.maxHpRatio >= 0.0) { "ambonMUD.engine.pets.maxHpRatio must be >= 0" }
        require(engine.pets.maxDamageRatio >= 0.0) { "ambonMUD.engine.pets.maxDamageRatio must be >= 0" }
        require(engine.pets.maxArmorRatio >= 0.0) { "ambonMUD.engine.pets.maxArmorRatio must be >= 0" }
        engine.pets.definitions.forEach { (key, tmpl) ->
            require(tmpl.hpRatio >= 0.0) { "ambonMUD.engine.pets.definitions.$key.hpRatio must be >= 0" }
            require(tmpl.damageRatio >= 0.0) { "ambonMUD.engine.pets.definitions.$key.damageRatio must be >= 0" }
            require(tmpl.armorRatio >= 0.0) { "ambonMUD.engine.pets.definitions.$key.armorRatio must be >= 0" }
            require(tmpl.baseHp > 0) { "ambonMUD.engine.pets.definitions.$key.baseHp must be > 0" }
            require(tmpl.baseMinDamage > 0) { "ambonMUD.engine.pets.definitions.$key.baseMinDamage must be > 0" }
            require(tmpl.baseMaxDamage >= tmpl.baseMinDamage) {
                "ambonMUD.engine.pets.definitions.$key.baseMaxDamage (${tmpl.baseMaxDamage}) must be >= baseMinDamage (${tmpl.baseMinDamage})"
            }
            require(tmpl.baseArmor >= 0) { "ambonMUD.engine.pets.definitions.$key.baseArmor must be >= 0" }
        }
    }

    private fun validateEngineBank() {
        engine.bank.maxItems.requirePositive("ambonMUD.engine.bank.maxItems")
    }

    private fun validateEngineStylist() {
        require(engine.stylist.feeGold >= 0) { "ambonMUD.engine.stylist.feeGold must be >= 0" }
    }

    private fun validateEngineAkathavae() {
        val a = engine.akathavae
        require(a.renounceCostGold >= 0) { "ambonMUD.engine.akathavae.renounceCostGold must be >= 0" }
        require(a.repledgeCooldownMs >= 0) { "ambonMUD.engine.akathavae.repledgeCooldownMs must be >= 0" }
        require(a.illuminateBaseSuccessPct in 0..100) { "ambonMUD.engine.akathavae.illuminateBaseSuccessPct must be 0..100" }
        require(a.minSuccessPct in 0..100 && a.maxSuccessPct in 0..100 && a.minSuccessPct <= a.maxSuccessPct) {
            "ambonMUD.engine.akathavae.minSuccessPct/maxSuccessPct must be 0..100 with min <= max"
        }
        require(a.repeatXpFraction in 0.0..1.0) { "ambonMUD.engine.akathavae.repeatXpFraction must be 0..1" }
        require(a.failRetryCooldownMs >= 0) { "ambonMUD.engine.akathavae.failRetryCooldownMs must be >= 0" }
        require(a.repeatXpCooldownMs >= 0) { "ambonMUD.engine.akathavae.repeatXpCooldownMs must be >= 0" }
        require(a.discoveryXpThrottleMs >= 0) { "ambonMUD.engine.akathavae.discoveryXpThrottleMs must be >= 0" }
        require(a.roomDiscoveryXp >= 0 && a.itemDiscoveryXp >= 0 && a.observeNpcXp >= 0) {
            "ambonMUD.engine.akathavae discovery XP values must be >= 0"
        }
    }

    private fun validateEngineWorldTime() {
        engine.worldTime.cycleLengthMs.requirePositive("ambonMUD.engine.worldTime.cycleLengthMs")
        require(engine.worldTime.dawnHour in 0..23) { "ambonMUD.engine.worldTime.dawnHour must be 0..23" }
        require(engine.worldTime.dayHour in 0..23) { "ambonMUD.engine.worldTime.dayHour must be 0..23" }
        require(engine.worldTime.duskHour in 0..23) { "ambonMUD.engine.worldTime.duskHour must be 0..23" }
        require(engine.worldTime.nightHour in 0..23) { "ambonMUD.engine.worldTime.nightHour must be 0..23" }
        require(engine.worldTime.dawnHour < engine.worldTime.dayHour) {
            "ambonMUD.engine.worldTime.dawnHour (${engine.worldTime.dawnHour}) must be < dayHour (${engine.worldTime.dayHour})"
        }
        require(engine.worldTime.dayHour < engine.worldTime.duskHour) {
            "ambonMUD.engine.worldTime.dayHour (${engine.worldTime.dayHour}) must be < duskHour (${engine.worldTime.duskHour})"
        }
        require(engine.worldTime.duskHour < engine.worldTime.nightHour) {
            "ambonMUD.engine.worldTime.duskHour (${engine.worldTime.duskHour}) must be < nightHour (${engine.worldTime.nightHour})"
        }
    }

    private fun validateEngineWeather() {
        engine.weather.minTransitionMs.requirePositive("ambonMUD.engine.weather.minTransitionMs")
        require(engine.weather.maxTransitionMs >= engine.weather.minTransitionMs) {
            "ambonMUD.engine.weather.maxTransitionMs must be >= minTransitionMs"
        }
        require(engine.weather.types.isNotEmpty()) {
            "ambonMUD.engine.weather.types must not be empty"
        }
        engine.weather.types.forEach { (id, def) ->
            require(def.weight > 0) {
                "ambonMUD.engine.weather.types[$id].weight must be positive"
            }
        }
    }

    private fun validateEngineWorldEvents() {
        engine.worldEvents.definitions.forEach { (id, def) ->
            val path = "ambonMUD.engine.worldEvents.definitions[$id]"
            for ((field, value) in listOf("startDate" to def.startDate, "endDate" to def.endDate)) {
                if (value.isNotEmpty()) {
                    require(runCatching { java.time.LocalDate.parse(value) }.isSuccess) {
                        "$path.$field must be an ISO date (yyyy-MM-dd), got '$value'"
                    }
                }
            }
            def.recurrence?.let { rec ->
                rec.periodMs.requirePositive("$path.recurrence.periodMs")
                require(rec.durationMs in 1 until rec.periodMs) {
                    "$path.recurrence.durationMs must be in 1..${rec.periodMs - 1} (less than periodMs), got ${rec.durationMs}"
                }
                require(rec.offsetMs >= 0) {
                    "$path.recurrence.offsetMs must be >= 0, got ${rec.offsetMs}"
                }
            }
        }
    }

    private fun validateEngineEnchanting() {
        engine.enchanting.maxEnchantmentsPerItem.requirePositive("ambonMUD.engine.enchanting.maxEnchantmentsPerItem")
        engine.enchanting.definitions.forEach { (key, def) ->
            require(def.displayName.isNotBlank()) { "ambonMUD.engine.enchanting.definitions.$key.displayName must be non-blank" }
            require(def.materials.isNotEmpty()) { "ambonMUD.engine.enchanting.definitions.$key.materials must not be empty" }
            require(def.skillRequired > 0) { "ambonMUD.engine.enchanting.definitions.$key.skillRequired must be > 0" }
        }
    }

    private fun validateEngineFactions() {
        val factionIds = engine.factions.definitions.keys
        for ((factionId, def) in engine.factions.definitions) {
            for (enemyId in def.enemies) {
                require(enemyId in factionIds) {
                    "faction '$factionId' references enemy '$enemyId' which is not defined in factions.definitions"
                }
            }
        }
        val tiers = engine.factions.tiers
        if (tiers != null) {
            require(tiers.isNotEmpty()) { "ambonMUD.engine.factions.tiers must not be empty when set" }
            val seenIds = mutableSetOf<String>()
            for (tier in tiers) {
                require(tier.id.isNotBlank()) { "ambonMUD.engine.factions.tiers entry has blank id" }
                require(tier.label.isNotBlank()) { "ambonMUD.engine.factions.tiers['${tier.id}'].label must be non-blank" }
                require(seenIds.add(tier.id)) {
                    "ambonMUD.engine.factions.tiers has duplicate id '${tier.id}'"
                }
            }
            val sorted = tiers.sortedBy { it.minReputation }
            for (i in 1 until sorted.size) {
                require(sorted[i].minReputation > sorted[i - 1].minReputation) {
                    "ambonMUD.engine.factions.tiers has duplicate minReputation ${sorted[i].minReputation}"
                }
            }
        }
    }

    private fun validateEngineAutoQuests() {
        val aq = engine.autoQuests
        if (!aq.enabled) return
        require(aq.timeLimitMs > 0) { "ambonMUD.engine.autoQuests.timeLimitMs must be > 0" }
        require(aq.cooldownMs >= 0) { "ambonMUD.engine.autoQuests.cooldownMs must be >= 0" }
        require(aq.rewardGoldBase >= 0) { "ambonMUD.engine.autoQuests.rewardGoldBase must be >= 0" }
        require(aq.rewardGoldPerLevel >= 0) { "ambonMUD.engine.autoQuests.rewardGoldPerLevel must be >= 0" }
        require(aq.rewardXpBase >= 0) { "ambonMUD.engine.autoQuests.rewardXpBase must be >= 0" }
        require(aq.rewardXpPerLevel >= 0) { "ambonMUD.engine.autoQuests.rewardXpPerLevel must be >= 0" }
        require(aq.killCountMin >= 1) { "ambonMUD.engine.autoQuests.killCountMin must be >= 1" }
        require(aq.killCountMax >= aq.killCountMin) { "ambonMUD.engine.autoQuests.killCountMax must be >= killCountMin" }
    }

    private fun validateEngineGlobalQuests() {
        if (!engine.globalQuests.enabled) return
        val gq = engine.globalQuests
        gq.intervalMs.requirePositive("ambonMUD.engine.globalQuests.intervalMs")
        gq.durationMs.requirePositive("ambonMUD.engine.globalQuests.durationMs")
        gq.announceIntervalMs.requirePositive("ambonMUD.engine.globalQuests.announceIntervalMs")
        require(gq.minPlayersOnline >= 1) {
            "ambonMUD.engine.globalQuests.minPlayersOnline must be >= 1, got ${gq.minPlayersOnline}"
        }
        require(gq.rewardGoldFirst >= 0) { "ambonMUD.engine.globalQuests.rewardGoldFirst must be >= 0" }
        require(gq.rewardGoldSecond >= 0) { "ambonMUD.engine.globalQuests.rewardGoldSecond must be >= 0" }
        require(gq.rewardGoldThird >= 0) { "ambonMUD.engine.globalQuests.rewardGoldThird must be >= 0" }
        require(gq.rewardXpFirst >= 0) { "ambonMUD.engine.globalQuests.rewardXpFirst must be >= 0" }
        require(gq.rewardXpSecond >= 0) { "ambonMUD.engine.globalQuests.rewardXpSecond must be >= 0" }
        require(gq.rewardXpThird >= 0) { "ambonMUD.engine.globalQuests.rewardXpThird must be >= 0" }
        require(gq.objectives.isNotEmpty()) { "ambonMUD.engine.globalQuests.objectives must not be empty" }
        for ((i, obj) in gq.objectives.withIndex()) {
            require(obj.targetCount > 0) {
                "ambonMUD.engine.globalQuests.objectives[$i].targetCount must be > 0"
            }
            require(obj.description.isNotBlank()) {
                "ambonMUD.engine.globalQuests.objectives[$i].description must be non-blank"
            }
        }
    }

    private fun validateEngineLottery() {
        if (!engine.lottery.enabled) return
        require(engine.lottery.ticketCost > 0) { "ambonMUD.engine.lottery.ticketCost must be > 0" }
        require(engine.lottery.drawingIntervalMs > 0) { "ambonMUD.engine.lottery.drawingIntervalMs must be > 0" }
        require(engine.lottery.jackpotSeedGold >= 0) { "ambonMUD.engine.lottery.jackpotSeedGold must be >= 0" }
        require(engine.lottery.jackpotPercentFromTickets in 0..100) {
            "ambonMUD.engine.lottery.jackpotPercentFromTickets must be in 0..100"
        }
        require(engine.lottery.maxTicketsPerPlayer > 0) { "ambonMUD.engine.lottery.maxTicketsPerPlayer must be > 0" }
    }

    private fun validateEngineGambling() {
        if (!engine.gambling.enabled) return
        require(engine.gambling.diceMinBet > 0) { "ambonMUD.engine.gambling.diceMinBet must be > 0" }
        require(engine.gambling.diceMaxBet >= engine.gambling.diceMinBet) {
            "ambonMUD.engine.gambling.diceMaxBet must be >= diceMinBet"
        }
        require(engine.gambling.diceWinMultiplier > 0.0) { "ambonMUD.engine.gambling.diceWinMultiplier must be > 0" }
        require(engine.gambling.diceWinTarget in 6..52) { "ambonMUD.engine.gambling.diceWinTarget must be in 6..52" }
        require(engine.gambling.coinMaxThreshold in 1..6) { "ambonMUD.engine.gambling.coinMaxThreshold must be in 1..6" }
        require(engine.gambling.coinWinMultiplier > 0.0) { "ambonMUD.engine.gambling.coinWinMultiplier must be > 0" }
        require(engine.gambling.coinJackpotMultiplier > 0.0) {
            "ambonMUD.engine.gambling.coinJackpotMultiplier must be > 0"
        }
        require(engine.gambling.cooldownMs >= 0) { "ambonMUD.engine.gambling.cooldownMs must be >= 0" }
    }

    private fun validateEngineJukebox() {
        if (!engine.jukebox.enabled) return
        require(engine.jukebox.maxSongDurationSeconds > 0) {
            "ambonMUD.engine.jukebox.maxSongDurationSeconds must be > 0"
        }
    }

    private fun validateProgression() {
        progression.maxLevel.requirePositive("ambonMUD.progression.maxLevel")
        progression.xp.baseXp.requirePositive("ambonMUD.progression.xp.baseXp")
        require(progression.xp.exponent >= 1.0) {
            "ambonMUD.progression.xp.exponent must be >= 1.0 to ensure XP requirements increase with level"
        }
        require(progression.xp.linearXp >= 0L) { "ambonMUD.progression.xp.linearXp must be >= 0" }
        require(progression.xp.multiplier >= 0.0) { "ambonMUD.progression.xp.multiplier must be >= 0" }
        require(progression.xp.defaultKillXp >= 0L) { "ambonMUD.progression.xp.defaultKillXp must be >= 0" }
        progression.xp.diminishing.thresholds.forEachIndexed { index, t ->
            require(t.levelsBelow >= 0) {
                "ambonMUD.progression.xp.diminishing.thresholds[$index].levelsBelow must be >= 0"
            }
            require(t.multiplier in 0.0..1.0) {
                "ambonMUD.progression.xp.diminishing.thresholds[$index].multiplier must be in [0.0, 1.0]"
            }
        }
        require(progression.rewards.hpScalingRate >= 1.0) {
            "ambonMUD.progression.rewards.hpScalingRate must be >= 1.0"
        }
        require(progression.rewards.manaScalingRate >= 1.0) {
            "ambonMUD.progression.rewards.manaScalingRate must be >= 1.0"
        }
        if (progression.rewards.hpScalingRate > MAX_SCALING_RATE) {
            warnConfig(
                "progression.rewards.hpScalingRate is ${progression.rewards.hpScalingRate}, " +
                    "expected <= $MAX_SCALING_RATE",
            )
        }
        if (progression.rewards.manaScalingRate > MAX_SCALING_RATE) {
            warnConfig(
                "progression.rewards.manaScalingRate is ${progression.rewards.manaScalingRate}, " +
                    "expected <= $MAX_SCALING_RATE",
            )
        }
        require(progression.rewards.baseHp >= 1) { "ambonMUD.progression.rewards.baseHp must be >= 1" }
        require(progression.rewards.baseMana >= 0) { "ambonMUD.progression.rewards.baseMana must be >= 0" }
        require(progression.quests.baseline.baseXp >= 0L) { "ambonMUD.progression.quests.baseline.baseXp must be >= 0" }
        require(progression.quests.baseline.xpPerLevel >= 0L) { "ambonMUD.progression.quests.baseline.xpPerLevel must be >= 0" }
        for ((difficulty, multiplier) in progression.quests.tiers) {
            require(multiplier >= 0.0) {
                "ambonMUD.progression.quests.tiers[$difficulty] must be >= 0 (got $multiplier)"
            }
        }
    }

    private fun validateTransport() {
        transport.telnet.maxLineLen.requirePositive("ambonMUD.transport.telnet.maxLineLen")
        require(transport.telnet.maxNonPrintablePerLine >= 0) {
            "ambonMUD.transport.telnet.maxNonPrintablePerLine must be >= 0"
        }
        transport.telnet.socketBacklog.requirePositive("ambonMUD.transport.telnet.socketBacklog")
        transport.telnet.maxConnections.requirePositive("ambonMUD.transport.telnet.maxConnections")
        transport.maxInboundBackpressureFailures.requirePositive("ambonMUD.transport.maxInboundBackpressureFailures")

        require(transport.websocket.host.isNotBlank()) { "ambonMUD.transport.websocket.host must be non-blank" }
        require(transport.websocket.stopGraceMillis >= 0L) { "ambonMUD.transport.websocket.stopGraceMillis must be >= 0" }
        require(transport.websocket.stopTimeoutMillis >= 0L) { "ambonMUD.transport.websocket.stopTimeoutMillis must be >= 0" }
    }

    private fun validateDemo() {
        require(demo.webClientHost.isNotBlank()) { "ambonMUD.demo.webClientHost must be non-blank" }
    }

    private fun validateObservability() {
        require(observability.metricsEndpoint.startsWith("/")) {
            "ambonMUD.observability.metricsEndpoint must start with '/'"
        }
        observability.metricsHttpPort.requireValidPort("ambonMUD.observability.metricsHttpPort")

        if (mode == DeploymentMode.ENGINE && observability.metricsEnabled) {
            require(grpc.server.port != observability.metricsHttpPort) {
                "ambonMUD.grpc.server.port (${grpc.server.port}) and " +
                    "ambonMUD.observability.metricsHttpPort (${observability.metricsHttpPort}) " +
                    "must not be the same in ENGINE mode — both listeners would bind to the same port"
            }
        }

        if (observability.metricsEnabled &&
            observability.metricsHttpHost == "0.0.0.0" &&
            mode != DeploymentMode.STANDALONE
        ) {
            warnConfig(
                "ambonMUD.observability.metricsHttpHost is 0.0.0.0 (all interfaces) in ${mode.name} mode. " +
                    "Consider binding to 127.0.0.1 to restrict access.",
            )
        }
    }

    private fun validateAdmin() {
        if (admin.enabled) {
            admin.port.requireValidPort("ambonMUD.admin.port")
            require(admin.token.isNotBlank()) { "ambonMUD.admin.token must be non-blank when admin.enabled=true" }
        }

        if ("*" in admin.corsOrigins) {
            warnConfig("admin.corsOrigins contains wildcard '*' — this allows any origin and should not be used in production")
        }
    }

    private fun validateRedis() {
        if (redis.enabled) {
            require(redis.uri.isNotBlank()) { "ambonMUD.redis.uri must be non-blank when redis.enabled=true" }
            redis.cacheTtlSeconds.requirePositive("ambonMUD.redis.cacheTtlSeconds")
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
    }

    private fun validateGrpc() {
        if (mode == DeploymentMode.ENGINE || mode == DeploymentMode.GATEWAY) {
            grpc.server.port.requireValidPort("ambonMUD.grpc.server.port")
            require(grpc.sharedSecret.isNotBlank()) {
                "ambonMUD.grpc.sharedSecret must be non-blank in ENGINE/GATEWAY mode"
            }
            grpc.timestampToleranceMs.requirePositive("ambonMUD.grpc.timestampToleranceMs")
        }
    }

    private fun validateGateway() {
        if (mode == DeploymentMode.GATEWAY) {
            require(grpc.client.engineHost.isNotBlank()) { "ambonMUD.grpc.client.engineHost must be non-blank in gateway mode" }
            grpc.client.enginePort.requireValidPort("ambonMUD.grpc.client.enginePort")
            require(gateway.id in 0..0xFFFF) { "ambonMUD.gateway.id must be between 0 and 65535" }
            gateway.snowflake.idLeaseTtlSeconds.requirePositive("ambonMUD.gateway.snowflake.idLeaseTtlSeconds")
            gateway.reconnect.maxAttempts.requirePositive("ambonMUD.gateway.reconnect.maxAttempts")
            gateway.reconnect.initialDelayMs.requirePositive("ambonMUD.gateway.reconnect.initialDelayMs")
            require(gateway.reconnect.maxDelayMs >= gateway.reconnect.initialDelayMs) {
                "ambonMUD.gateway.reconnect.maxDelayMs must be >= initialDelayMs"
            }
            require(gateway.reconnect.jitterFactor in 0.0..1.0) {
                "ambonMUD.gateway.reconnect.jitterFactor must be in 0.0..1.0"
            }
            gateway.reconnect.streamVerifyMs.requirePositive("ambonMUD.gateway.reconnect.streamVerifyMs")

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
    }

    private fun validateSharding() {
        if (sharding.enabled) {
            require(sharding.engineId.isNotBlank()) { "ambonMUD.sharding.engineId must be non-blank when sharding.enabled=true" }
            sharding.handoff.ackTimeoutMs.requirePositive("ambonMUD.sharding.handoff.ackTimeoutMs")
            sharding.registry.leaseTtlSeconds.requirePositive("ambonMUD.sharding.registry.leaseTtlSeconds")
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
                sharding.playerIndex.heartbeatMs.requirePositive("ambonMUD.sharding.playerIndex.heartbeatMs")
            }

            if (sharding.instancing.enabled) {
                sharding.instancing.defaultCapacity.requirePositive("ambonMUD.sharding.instancing.defaultCapacity")
                sharding.instancing.loadReportIntervalMs.requirePositive("ambonMUD.sharding.instancing.loadReportIntervalMs")
                require(sharding.instancing.startZoneMinInstances >= 1) {
                    "ambonMUD.sharding.instancing.startZoneMinInstances must be >= 1"
                }
                if (sharding.instancing.autoScale.enabled) {
                    sharding.instancing.autoScale.evaluationIntervalMs.requirePositive(
                        "ambonMUD.sharding.instancing.autoScale.evaluationIntervalMs",
                    )
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
                    sharding.instancing.autoScale.cooldownMs.requirePositive("ambonMUD.sharding.instancing.autoScale.cooldownMs")
                }
            }
        }
    }

    private fun validateCrossCuttingDependencies() {
        if (sharding.enabled) {
            require(redis.enabled) {
                "ambonMUD.redis.enabled must be true when sharding.enabled=true (sharding requires Redis)"
            }
        }
        if (sharding.instancing.enabled) {
            require(sharding.enabled) {
                "ambonMUD.sharding.enabled must be true when sharding.instancing.enabled=true"
            }
        }
    }

    private fun validateProductionMode() {
        if (server.productionMode) {
            val forbiddenPasswords = setOf("changeme", "ambon", "password", "")
            require(database.password.lowercase() !in forbiddenPasswords) {
                "ambonMUD.database.password must not be a placeholder value ('${database.password}') " +
                    "when server.productionMode=true"
            }
            if (redis.enabled && redis.bus.enabled) {
                val forbiddenSecrets = setOf("CHANGE_ME", "changeme", "")
                require(redis.bus.sharedSecret !in forbiddenSecrets) {
                    "ambonMUD.redis.bus.sharedSecret must not be a placeholder value " +
                        "when server.productionMode=true and redis.bus.enabled=true"
                }
            }
            if (admin.enabled) {
                val forbiddenTokens = setOf("changeme", "admin", "")
                require(admin.token.lowercase() !in forbiddenTokens) {
                    "ambonMUD.admin.token must not be a placeholder value ('${admin.token}') " +
                        "when server.productionMode=true and admin.enabled=true"
                }
            }
            if (mode == DeploymentMode.ENGINE || mode == DeploymentMode.GATEWAY) {
                val forbiddenSecrets = setOf("change_me", "changeme", "secret", "")
                require(grpc.sharedSecret.lowercase() !in forbiddenSecrets) {
                    "ambonMUD.grpc.sharedSecret must not be a placeholder value " +
                        "when server.productionMode=true in ENGINE/GATEWAY mode"
                }
                require(grpc.sharedSecret.length >= 16) {
                    "ambonMUD.grpc.sharedSecret must be at least 16 characters when server.productionMode=true " +
                        "in ENGINE/GATEWAY mode (gRPC auth HMAC strength)"
                }
            }
        }
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
    /** When true, placeholder/default secrets are rejected at startup. */
    val productionMode: Boolean = false,
)

data class WorldConfig(
    val resources: List<String> = emptyList(),
    val startRoom: String? = null,
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
    /** Maximum number of sessions simultaneously progressing through the login/auth funnel. */
    val maxConcurrentLogins: Int = 50,
    /** Thread-pool size for BCrypt hashing, isolated from the shared Dispatchers.IO pool. */
    val authThreads: Int = Runtime.getRuntime().availableProcessors(),
)

data class EconomyConfig(
    val buyMultiplier: Double = 1.0,
    val sellMultiplier: Double = 0.5,
)

data class LotteryConfig(
    val enabled: Boolean = true,
    val ticketCost: Long = 100L,
    val drawingIntervalMs: Long = 3_600_000L,
    val jackpotSeedGold: Long = 500L,
    /** Percentage of ticket sales added to the jackpot (0–100). */
    val jackpotPercentFromTickets: Int = 80,
    val maxTicketsPerPlayer: Int = 10,
)

data class GamblingConfig(
    val enabled: Boolean = true,
    val diceMinBet: Long = 10L,
    val diceMaxBet: Long = 10_000L,
    /**
     * Aineroia's Dice. Six dice — the goddess's children — are rolled in
     * descending size: the large pair (Ophirae/Mycorae, d20), the medium pair
     * (Pyrae/Aetherae, d16), the small pair (Lustriae/Aureliae, d8). Their sum
     * decides the base wager; the Luneqrae coin decides the miracle.
     */
    val diceWinMultiplier: Double = 2.0,
    /** A summed roll at or below this target wins the base payout (mean roll ≈ 47). */
    val diceWinTarget: Int = 45,
    /** This many dice (or more) landing on their max face summons the Luneqrae coin flip. */
    val coinMaxThreshold: Int = 3,
    /** Payout multiplier when the Luneqrae coin flip wins (and the sum busted). */
    val coinWinMultiplier: Double = 10.0,
    /** Payout multiplier when the coin flip wins AND the sum was at or below the target. */
    val coinJackpotMultiplier: Double = 12.0,
    /** Cooldown between gamble attempts in milliseconds. */
    val cooldownMs: Long = 5_000L,
)

/**
 * Room jukebox: players pay an authored per-song gold cost to play a track for
 * everyone in the room. [maxSongDurationSeconds] caps how long any one paid track
 * can lock a room (validated against each song at world load is left to authors;
 * this is a sanity bound surfaced via [AppConfig.validated]).
 */
data class JukeboxConfig(
    val enabled: Boolean = true,
    val maxSongDurationSeconds: Int = 600,
)

data class CraftingConfig(
    val maxSkillLevel: Int = 100,
    val baseXpPerLevel: Long = 50L,
    val xpExponent: Double = 1.5,
    val gatherCooldownMs: Long = 3000L,
    val stationBonusQuantity: Int = 1,
    /** XP multiplier bonus for the player's specialized skill (e.g. 0.25 = +25% XP). */
    val specializationXpBonus: Double = 0.25,
    val recipes: Map<String, RecipeConfigEntry> = emptyMap(),
)

data class FactionDefinition(
    val name: String = "",
    val description: String = "",
    val enemies: List<String> = emptyList(),
)

data class CurrencyDefinitionConfig(
    val displayName: String = "",
    val abbreviation: String = "",
    val description: String = "",
)

data class CurrenciesConfig(
    val definitions: Map<String, CurrencyDefinitionConfig> = emptyMap(),
    /** Honor points awarded per PvP kill. */
    val honorPerPvpKill: Long = 10L,
    /** Crafting tokens awarded per successful craft. */
    val tokensPerCraft: Long = 1L,
)

data class PetTemplateConfig(
    val name: String = "a pet",
    val description: String = "",
    /**
     * Pet HP as a fraction of the owner's effective maxHp. 1.0 = same as owner.
     * Combined with [baseHp] as a floor so low-level summons still feel substantial.
     */
    val hpRatio: Double = 0.6,
    /** Pet melee damage as a fraction of the owner's displayed damage range. */
    val damageRatio: Double = 0.5,
    /** Pet armor as a fraction of the owner's equipped armor. */
    val armorRatio: Double = 0.4,
    /** Floor applied to scaled HP. Also used when no owner stats are available. */
    val baseHp: Int = 20,
    /** Floor applied to scaled min damage. */
    val baseMinDamage: Int = 1,
    /** Floor applied to scaled max damage. */
    val baseMaxDamage: Int = 4,
    /** Floor applied to scaled armor. */
    val baseArmor: Int = 0,
    val image: String? = null,
    val spells: Map<String, PetSpellConfig> = emptyMap(),
    val defaultAttack: String? = null,
    /** Threat multiplier for pet attacks. 0.0 = no threat (DPS pet), >0 = tank pet that holds aggro. */
    val threatMultiplier: Double = 0.0,
)

data class PetSpellConfig(
    val displayName: String = "",
    val message: String = "",
    val roomMessage: String = "",
    /**
     * Spell damage as a multiple of the pet's scaled melee swing. 1.0 = same as a normal
     * swing; 2.0 = twice as hard. When set, the pet's already-scaled damage range is used as
     * the anchor, so spells inherit level/gear scaling for free.
     */
    val damageRatio: Double? = null,
    /** Heal as a fraction of the owner's maxHp. Used when [healMin]/[healMax] are not set. */
    val healRatio: Double? = null,
    /** Absolute fallback damage range, used when [damageRatio] is null. */
    val minDamage: Int? = null,
    val maxDamage: Int? = null,
    /** Absolute fallback heal range, used when [healRatio] is null. */
    val healMin: Int = 0,
    val healMax: Int = 0,
    val statusEffectId: String? = null,
    val cooldownMs: Long = 0L,
    val weight: Int = 1,
    /** Flat threat added to the pet's threat entry on the target (for tank-pet taunt skills). */
    val threatBonus: Double = 0.0,
    /** Optional asset filename used as the quickbar / spellbook icon for this skill. */
    val image: String? = null,
)

data class PetConfig(
    val definitions: Map<String, PetTemplateConfig> = emptyMap(),
    /**
     * Grace window after a manual `pet <skill>` trigger during which the pet will NOT auto-cast
     * a skill — instead it falls back to its melee/default attack. Keeps manual rotations from
     * being clobbered by auto-cast.
     */
    val manualSkillGraceMs: Long = 8_000L,
    /** Global cap on [PetTemplateConfig.hpRatio]. Prevents a single template from trivializing content. */
    val maxHpRatio: Double = 1.0,
    /** Global cap on [PetTemplateConfig.damageRatio]. Prevents gear-stacked pets from out-DPS'ing the player. */
    val maxDamageRatio: Double = 0.8,
    /** Global cap on [PetTemplateConfig.armorRatio]. */
    val maxArmorRatio: Double = 1.0,
)

data class BankConfig(
    val maxItems: Int = 50,
)

data class StylistConfig(
    /** Gold fee charged to change race at a stylist NPC. */
    val feeGold: Long = 500,
)

/**
 * Tuning for the Akathavae pledge — the pacifist explorer path. Pledging is free at
 * any Akathavae shrine; renouncing the vow at a shrine costs gold, and re-pledging
 * after a renunciation is gated behind a cooldown so players can't flip between the
 * paths to double-dip rewards.
 *
 * Illumination is the pledged player's replacement for combat: a stat-driven attempt
 * to record a creature in their Arcanum. Success is a kill-equivalent (drops, gold,
 * quest credit, XP); failure turns the subject hostile unless the player talks their
 * way out. The stat bindings mirror the combat system's configurable stat keys.
 */
data class AkathavaeConfig(
    val enabled: Boolean = true,
    /** Gold cost to renounce the pledge at a shrine. */
    val renounceCostGold: Long = 2_500,
    /** Real-time cooldown before an ex-Akathavae may pledge again (ms). Default 24h. */
    val repledgeCooldownMs: Long = 86_400_000,
    /** [Illumination resolution] Base chance (percent) that a success roll passes before stat/level adjustments. */
    val illuminateBaseSuccessPct: Int = 70,
    /** Stat that improves illumination success chance. */
    val successStat: String = "INT",
    /** Success-chance percent gained per [successStat] point above base (10). */
    val successPerStatPoint: Double = 2.0,
    /** Success-chance percent lost per level the subject is above the player. */
    val levelGapPenaltyPct: Double = 8.0,
    /** Stat that shrinks the level-gap penalty (a steady hand up close). */
    val gapReliefStat: String = "STR",
    /** Gap-penalty percent (per subject level) removed per [gapReliefStat] point above base. */
    val gapReliefPerStatPoint: Double = 0.5,
    /** Success chance is clamped into [minSuccessPct]..[maxSuccessPct]. */
    val minSuccessPct: Int = 5,
    val maxSuccessPct: Int = 95,
    /** Per-subject retry cooldown after a failed illumination (ms). */
    val failRetryCooldownMs: Long = 30_000,
    /** Stat that lets a failed illuminator talk their way out of being attacked. */
    val escapeStat: String = "CHA",
    /** Escape-chance percent per [escapeStat] point above base on a failed illumination. */
    val escapePerStatPoint: Double = 3.0,
    /** [Discovery XP] Stat that scales all illumination/discovery XP yields. */
    val xpStat: String = "WIS",
    /** Fractional XP bonus per [xpStat] point above base (0.02 = +2% per point). */
    val xpBonusPerStatPoint: Double = 0.02,
    /** Fraction of the first-time XP awarded for re-illuminating a known subject. */
    val repeatXpFraction: Double = 0.2,
    /** Per-subject cooldown before a repeat illumination yields XP again (ms). */
    val repeatXpCooldownMs: Long = 300_000,
    /** XP for recording a never-before-visited room. */
    val roomDiscoveryXp: Long = 15,
    /** XP for recording a never-before-seen item. */
    val itemDiscoveryXp: Long = 25,
    /** XP for observing a non-combat NPC (vendors, quest givers — recorded, never removed). */
    val observeNpcXp: Long = 10,
    /** Minimum gap between discovery XP awards (ms) — anti-speedrun throttle. Entries still record. */
    val discoveryXpThrottleMs: Long = 1_500,
)

data class LeaderboardConfig(
    /** How often to refresh the leaderboard cache from player data (ms). Default: 5 minutes. */
    val refreshIntervalMs: Long = 300_000L,
    /** Maximum number of entries per leaderboard category. */
    val topN: Int = 10,
)

data class PrestigeConfig(
    /** Whether the prestige system is enabled. */
    val enabled: Boolean = true,
    /** Base XP cost for the first prestige rank. */
    val xpCostBase: Long = 500_000,
    /** Multiplicative factor applied to the cost for each subsequent rank. */
    val xpCostMultiplier: Double = 1.5,
    /** Maximum prestige rank a player can achieve. */
    val maxRank: Int = 20,
    /** Per-rank perk definitions keyed by rank number. */
    val perks: Map<Int, PrestigePerkConfig> = emptyMap(),
)

data class PrestigePerkConfig(
    /** Perk type: STAT_BONUS, SKILL_POINT, TITLE, MAX_HP, MAX_MANA. */
    val type: String = "",
    /** Which stat for STAT_BONUS (STR, DEX, CON, INT, WIS, CHA, ALL). */
    val stat: String? = null,
    /** Numeric amount for the perk (stat points, skill points, HP, mana). */
    val amount: Int = 0,
    /** Title string for TITLE perks. */
    val title: String? = null,
    /** Human-readable description of the perk. */
    val description: String = "",
)

data class DailyQuestsConfig(
    /** Whether the daily/weekly quest system is enabled. */
    val enabled: Boolean = false,
    /** UTC hour at which daily quests reset (0–23). */
    val resetHourUtc: Int = 0,
    /** Number of daily quest slots available each day. */
    val dailySlots: Int = 3,
    /** Number of weekly quest slots available each week. */
    val weeklySlots: Int = 1,
    /** Percentage bonus per consecutive daily completion day (capped at streakMaxDays * this). */
    val streakBonusPercent: Int = 10,
    /** Maximum streak days that contribute to the bonus. */
    val streakMaxDays: Int = 7,
    /** Pool of possible daily quests. */
    val dailyPool: List<DailyQuestDefinition> = emptyList(),
    /** Pool of possible weekly quests. */
    val weeklyPool: List<DailyQuestDefinition> = emptyList(),
)

data class DailyQuestDefinition(
    /** Quest objective type: kill, gather, dungeon, craft, pvpKill. */
    val type: String = "kill",
    /** Number of actions required to complete. */
    val targetCount: Int = 10,
    /** Player-facing description. */
    val description: String = "",
    /** Gold rewarded on completion. */
    val goldReward: Long = 0L,
    /** XP rewarded on completion. */
    val xpReward: Long = 0L,
)

data class AutoQuestsConfig(
    /** Whether auto-generated bounty quests are enabled. */
    val enabled: Boolean = true,
    /** Time limit to complete an auto-quest (ms). */
    val timeLimitMs: Long = 600_000L,
    /** Cooldown between requesting auto-quests (ms). */
    val cooldownMs: Long = 60_000L,
    /** Base gold reward. */
    val rewardGoldBase: Long = 50L,
    /** Additional gold per player level. */
    val rewardGoldPerLevel: Long = 10L,
    /** Base XP reward. */
    val rewardXpBase: Long = 100L,
    /** Additional XP per player level. */
    val rewardXpPerLevel: Long = 25L,
    /** Minimum kill count for generated quests. */
    val killCountMin: Int = 3,
    /** Maximum kill count for generated quests. */
    val killCountMax: Int = 8,
)

data class WorldTimeConfig(
    /** Real-time milliseconds for one full game day (24 game hours). Default: 1 hour. */
    val cycleLengthMs: Long = 3_600_000L,
    val dawnHour: Int = 5,
    val dayHour: Int = 8,
    val duskHour: Int = 18,
    val nightHour: Int = 21,
)

data class SeasonConfig(
    /**
     * Real-time milliseconds for one full game year (all four seasons). Each
     * season lasts a quarter of this. Default: 4 hours (1 hour per season).
     */
    val cycleLengthMs: Long = 14_400_000L,
)

/**
 * Server-generated rare cosmetic variants. Every COMBAT-role mob can spawn as a
 * rare variant (unless an author opts it out) so explorers always have richer
 * sightings to find, even when content authors don't hand-author rare mobs.
 *
 * Variants are purely cosmetic plus a modest stat bump — a tint/overlay, a name
 * prefix, and small HP/XP/loot multipliers.
 */
data class MobVariantsConfig(
    val enabled: Boolean = true,
    /**
     * Base probability that an eligible mob rolls as a rare variant on each
     * spawn opportunity — cold start, zone reset, post-death respawn, and
     * conditional spawn — so a freshly-booted world is already seeded with a
     * few sightings to discover. Raise it for a denser initial population.
     */
    val chance: Double = 0.04,
    /** Variant archetypes keyed by ID, selected by [MobVariantDefinition.weight]. */
    val variants: Map<String, MobVariantDefinition> = DEFAULT_VARIANTS,
) {
    companion object {
        val DEFAULT_VARIANTS: Map<String, MobVariantDefinition> = mapOf(
            "albino" to MobVariantDefinition(
                displayName = "Albino",
                namePrefix = "Albino ",
                tint = "#f5f0ff",
                rarity = "uncommon",
                weight = 3.0,
                hpMultiplier = 1.2,
                xpMultiplier = 1.3,
                lootMultiplier = 1.2,
                announce = "ZONE",
            ),
            "verdant" to MobVariantDefinition(
                displayName = "Verdant",
                namePrefix = "Verdant ",
                tint = "#5fd17a",
                rarity = "uncommon",
                weight = 2.5,
                hpMultiplier = 1.2,
                xpMultiplier = 1.3,
                lootMultiplier = 1.2,
                announce = "ZONE",
            ),
            "shadow" to MobVariantDefinition(
                displayName = "Shadow-touched",
                namePrefix = "Shadow-touched ",
                tint = "#6a4aa0",
                overlay = "swirl",
                rarity = "uncommon",
                weight = 2.5,
                hpMultiplier = 1.25,
                xpMultiplier = 1.4,
                lootMultiplier = 1.25,
                announce = "ZONE",
            ),
            "ember" to MobVariantDefinition(
                displayName = "Ember",
                namePrefix = "Ember ",
                tint = "#ff6a3c",
                overlay = "embers",
                rarity = "rare",
                weight = 1.4,
                hpMultiplier = 1.5,
                xpMultiplier = 1.7,
                lootMultiplier = 1.5,
                announce = "ZONE",
            ),
            "glimmering" to MobVariantDefinition(
                displayName = "Glimmering",
                namePrefix = "Glimmering ",
                tint = "#ffe8a3",
                overlay = "sparkle",
                rarity = "rare",
                weight = 1.2,
                hpMultiplier = 1.5,
                xpMultiplier = 1.8,
                lootMultiplier = 1.6,
                announce = "ZONE",
            ),
            "frostbound" to MobVariantDefinition(
                displayName = "Frostbound",
                namePrefix = "Frostbound ",
                tint = "#a3e4ff",
                overlay = "frost",
                rarity = "rare",
                weight = 1.2,
                hpMultiplier = 1.5,
                xpMultiplier = 1.7,
                lootMultiplier = 1.5,
                announce = "ZONE",
            ),
            "spectral" to MobVariantDefinition(
                displayName = "Spectral",
                namePrefix = "Spectral ",
                tint = "#bfeaff",
                overlay = "mist",
                rarity = "legendary",
                weight = 0.4,
                hpMultiplier = 2.0,
                xpMultiplier = 2.5,
                lootMultiplier = 2.0,
                announce = "SERVER",
            ),
            "ancient" to MobVariantDefinition(
                displayName = "Ancient",
                namePrefix = "Ancient ",
                tint = "#caa86a",
                overlay = "swirl",
                rarity = "legendary",
                weight = 0.3,
                hpMultiplier = 2.2,
                xpMultiplier = 2.6,
                lootMultiplier = 2.2,
                announce = "SERVER",
            ),
        )
    }
}

/** A single server-generated rare variant archetype. */
data class MobVariantDefinition(
    val displayName: String = "",
    /** Prepended to the base mob name, e.g. "Shadow-touched ". */
    val namePrefix: String = "",
    /** CSS hex tint applied to the client sprite (multiply). Empty = no tint. */
    val tint: String = "",
    /** Client particle/overlay hint: swirl|embers|sparkle|frost|mist. Empty = none. */
    val overlay: String = "",
    /** uncommon|rare|legendary — flavor + default announce loudness. */
    val rarity: String = "uncommon",
    /** Relative selection weight among all variants. Higher = more common. */
    val weight: Double = 1.0,
    val hpMultiplier: Double = 1.0,
    val xpMultiplier: Double = 1.0,
    /** Multiplies drop chances (and gold) for this variant. */
    val lootMultiplier: Double = 1.0,
    /** Announcement scope on appearance: ROOM|ZONE|SERVER. */
    val announce: String = "ZONE",
)

data class WeatherConfig(
    /** Minimum real-time ms between weather transitions per zone. */
    val minTransitionMs: Long = 300_000L,
    /** Maximum real-time ms between weather transitions per zone. */
    val maxTransitionMs: Long = 900_000L,
    /** Weather type definitions keyed by ID (e.g. "CLEAR", "RAIN"). */
    val types: Map<String, WeatherTypeDefinition> = DEFAULT_WEATHER_TYPES,
) {
    companion object {
        val DEFAULT_WEATHER_TYPES: Map<String, WeatherTypeDefinition> = mapOf(
            "CLEAR" to WeatherTypeDefinition("Clear", "The sky is clear.", 3.0),
            "RAIN" to WeatherTypeDefinition("Rain", "A steady rain falls.", 2.0, "rain"),
            "STORM" to WeatherTypeDefinition("Storm", "Thunder rumbles and lightning splits the sky.", 0.5, "storm"),
            "FOG" to WeatherTypeDefinition("Fog", "A thick fog blankets the area.", 1.0, "fog"),
            "SNOW" to WeatherTypeDefinition("Snow", "Soft snow drifts down from above.", 0.8, "snow"),
            "WIND" to WeatherTypeDefinition("Wind", "A fierce wind howls through the area.", 1.0, "wind"),
        )
    }
}

/** A single weather type, fully config-driven. */
data class WeatherTypeDefinition(
    val displayName: String = "",
    val description: String = "",
    /** Relative probability weight for random transitions. Higher = more common. */
    val weight: Double = 1.0,
    /** Particle hint consumed by the web client (e.g. "rain", "snow", "fog"). Empty = no particles. */
    val particleHint: String = "",
    /** Icon hint for UI display (e.g. Unicode character). Empty = client default. */
    val icon: String = "",
)

/**
 * Optional repeating window for a world event. When present, the event is
 * active only during the first [durationMs] of every [periodMs] cycle
 * (cycles are anchored to the Unix epoch, shifted by [offsetMs]), further
 * bounded by the definition's date range. This is what makes an event
 * observable during normal play — e.g. ten minutes out of every hour —
 * rather than a one-shot calendar window.
 */
data class WorldEventRecurrence(
    /** Full cycle length in milliseconds (e.g. 3600000 = hourly). */
    val periodMs: Long = 0,
    /** Active window at the start of each cycle. Must be < periodMs. */
    val durationMs: Long = 0,
    /** Shifts the cycle anchor, e.g. to stagger multiple events. */
    val offsetMs: Long = 0,
)

data class WorldEventDefinition(
    val displayName: String = "",
    val description: String = "",
    /** ISO date string (yyyy-MM-dd) for event start, empty = always active. */
    val startDate: String = "",
    /** ISO date string (yyyy-MM-dd) for event end, empty = no end. */
    val endDate: String = "",
    /** Flags set on the world when event is active, queryable by quests/mobs. */
    val flags: List<String> = emptyList(),
    /** Announcement broadcast when event activates. */
    val startMessage: String = "",
    /** Announcement broadcast when event ends. */
    val endMessage: String = "",
    /** Repeating active window within the date range; null = active for the whole range. */
    val recurrence: WorldEventRecurrence? = null,
)

data class WorldEventsConfig(
    val definitions: Map<String, WorldEventDefinition> = emptyMap(),
)

// ---------- zone environment themes ----------

/** A hex color pair for ambient mote particles. Values are CSS-style "#rrggbb" strings. */
data class MoteColorEntry(
    val core: String = "#c8b8e8",
    val glow: String = "#a897d2",
)

/** Sky gradient colors for a single time period. Top/bottom define the vertical gradient. */
data class SkyGradient(
    val top: String = "#0a0c14",
    val bottom: String = "#1a1c2e",
)

/**
 * Visual environment theme for a zone, consumed by the web client via GMCP.
 * Arcanum authors can define these per-zone in config or zone YAML.
 */
data class ZoneEnvironmentTheme(
    /** Mote color palettes (each entry is one possible core/glow pair). */
    val moteColors: List<MoteColorEntry> = emptyList(),
    /** Sky gradient per time period. Keys: DAWN, DAY, DUSK, NIGHT. */
    val skyGradients: Map<String, SkyGradient> = emptyMap(),
    /** Mote colors used during room transitions. */
    val transitionColors: List<String> = emptyList(),
    /** Per-weather-type particle hint overrides. Keys are weather type IDs. */
    val weatherParticleOverrides: Map<String, String> = emptyMap(),
)

/** Global environment theme configuration with defaults and per-zone overrides. */
data class EnvironmentConfig(
    /** Default theme applied to all zones that don't specify their own. */
    val defaultTheme: ZoneEnvironmentTheme = ZoneEnvironmentTheme(
        moteColors = listOf(
            MoteColorEntry("#c8b8e8", "#a897d2"),
            MoteColorEntry("#d8c8f8", "#b8a8e0"),
            MoteColorEntry("#b8a8d8", "#9888c0"),
        ),
        skyGradients = mapOf(
            "DAWN" to SkyGradient("#2a1a3a", "#c88060"),
            "DAY" to SkyGradient("#4a6ea0", "#87ceeb"),
            "DUSK" to SkyGradient("#3a2040", "#c86848"),
            "NIGHT" to SkyGradient("#0a0c14", "#1a1c2e"),
        ),
        transitionColors = listOf("#c8b8e8", "#a897d2", "#8caec9", "#bea873", "#d8def1"),
    ),
    /** Per-zone theme overrides. Zone key = the zone prefix from RoomId (e.g. "tutorial_glade"). */
    val zones: Map<String, ZoneEnvironmentTheme> = emptyMap(),
)

data class EnchantmentDefinition(
    val displayName: String = "",
    val skill: String = "enchanting",
    val skillRequired: Int = 1,
    val materials: List<MaterialConfigEntry> = emptyList(),
    val statBonuses: Map<String, Int> = emptyMap(),
    val damageBonus: Int = 0,
    val armorBonus: Int = 0,
    /** Which equipment slot types this enchantment can be applied to. Empty = any slot. */
    val targetSlots: List<String> = emptyList(),
    val xpReward: Int = 30,
)

data class EnchantingConfig(
    val definitions: Map<String, EnchantmentDefinition> = emptyMap(),
    val maxEnchantmentsPerItem: Int = 1,
)

data class FactionConfig(
    val definitions: Map<String, FactionDefinition> = emptyMap(),
    val defaultReputation: Int = 0,
    /** Reputation lost with a mob's faction when killing that mob (base, scaled by level). */
    val killPenalty: Int = 5,
    /** Reputation gained with enemy factions when killing a mob (base, scaled by level). */
    val killBonus: Int = 3,
    /** Quest-specific reputation rewards: questId → { factionId → amount }. */
    val questRewards: Map<String, Map<String, Int>> = emptyMap(),
    /**
     * Reputation tiers for display and gating. Omit to use [ReputationTier.defaults].
     * When set, the lowest tier's minReputation acts as a floor (rep is clamped there),
     * and the highest tier's minReputation is the ceiling.
     */
    val tiers: List<ReputationTier>? = null,
) {
    /** Resolved tier list — config override, or built-in defaults when none configured. */
    fun resolvedTiers(): List<ReputationTier> =
        (tiers?.takeIf { it.isNotEmpty() } ?: ReputationTier.defaults)
            .sortedBy { it.minReputation }
}

data class ReputationTier(
    val id: String = "",
    val label: String = "",
    val minReputation: Int = 0,
) {
    companion object {
        /** Arcanum-aligned default tiers. */
        val defaults: List<ReputationTier> = listOf(
            ReputationTier("hated", "Hated", -20000),
            ReputationTier("hostile", "Hostile", -1000),
            ReputationTier("unfriendly", "Unfriendly", -500),
            ReputationTier("neutral", "Neutral", 0),
            ReputationTier("friendly", "Friendly", 250),
            ReputationTier("honored", "Honored", 1000),
            ReputationTier("revered", "Revered", 5000),
            ReputationTier("exalted", "Exalted", 20000),
        )
    }
}

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
            "enchanting" to CraftingSkillConfig(displayName = "Enchanting", type = "crafting"),
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
            "enchanting_table" to CraftingStationTypeConfig(displayName = "Enchanting Table"),
        )
    }
}

data class CharacterCreationConfig(
    val startingGold: Long = 0L,
    val defaultRace: String = "HUMAN",
    val defaultClass: String = "WARRIOR",
    val defaultGender: String = "enby",
    /**
     * When true, typing "demo" at the login name prompt spawns an ephemeral
     * demo character using the configured default race/class. The character
     * disappears at disconnect unless they run `/claim`.
     */
    val demoEnabled: Boolean = true,
)

data class EmotePresetConfig(
    val label: String = "",
    val emoji: String = "",
    val action: String = "",
)

data class EmotePresetsConfig(
    val presets: List<EmotePresetConfig> = defaultEmotePresets(),
) {
    companion object {
        fun defaultEmotePresets(): List<EmotePresetConfig> = listOf(
            EmotePresetConfig(label = "Wave", emoji = "\uD83D\uDC4B", action = "waves."),
            EmotePresetConfig(label = "Nod", emoji = "\uD83D\uDE42", action = "nods."),
            EmotePresetConfig(label = "Laugh", emoji = "\uD83D\uDE02", action = "laughs."),
            EmotePresetConfig(label = "Bow", emoji = "\uD83D\uDE4F", action = "bows respectfully."),
            EmotePresetConfig(label = "Cheer", emoji = "\uD83C\uDF89", action = "cheers!"),
            EmotePresetConfig(label = "Shrug", emoji = "\uD83E\uDD37", action = "shrugs."),
            EmotePresetConfig(label = "Clap", emoji = "\uD83D\uDC4F", action = "claps."),
            EmotePresetConfig(label = "Dance", emoji = "\uD83D\uDC83", action = "dances."),
            EmotePresetConfig(label = "Think", emoji = "\uD83E\uDD14", action = "thinks carefully."),
            EmotePresetConfig(label = "Facepalm", emoji = "\uD83E\uDD26", action = "facepalms."),
            EmotePresetConfig(label = "Salute", emoji = "\uD83E\uDEE1", action = "salutes."),
            EmotePresetConfig(label = "Cry", emoji = "\uD83D\uDE22", action = "cries."),
        )
    }
}

data class EquipmentSlotConfig(
    val displayName: String = "",
    val order: Int = 0,
    /** Paper-doll X position as a percentage (0–100) of the sprite width. */
    val x: Double = 50.0,
    /** Paper-doll Y position as a percentage (0–100) of the sprite height. */
    val y: Double = 50.0,
)

data class EquipmentConfig(
    val slots: Map<String, EquipmentSlotConfig> = defaultEquipmentSlots(),
) {
    companion object {
        fun defaultEquipmentSlots(): Map<String, EquipmentSlotConfig> = linkedMapOf(
            "head" to EquipmentSlotConfig(displayName = "Head", order = 0, x = 50.0, y = 8.0),
            "neck" to EquipmentSlotConfig(displayName = "Neck", order = 1, x = 50.0, y = 20.0),
            "body" to EquipmentSlotConfig(displayName = "Body", order = 2, x = 50.0, y = 40.0),
            "hands" to EquipmentSlotConfig(displayName = "Hands", order = 3, x = 20.0, y = 52.0),
            "weapon" to EquipmentSlotConfig(displayName = "Weapon", order = 4, x = 80.0, y = 52.0),
            "offhand" to EquipmentSlotConfig(displayName = "Offhand", order = 5, x = 20.0, y = 70.0),
            "feet" to EquipmentSlotConfig(displayName = "Feet", order = 6, x = 50.0, y = 90.0),
        )
    }
}

data class GenderConfig(
    val displayName: String = "",
)

data class GendersConfig(
    val genders: Map<String, GenderConfig> = defaultGenders(),
) {
    companion object {
        fun defaultGenders(): Map<String, GenderConfig> = linkedMapOf(
            "male" to GenderConfig(displayName = "Male"),
            "female" to GenderConfig(displayName = "Female"),
            "enby" to GenderConfig(displayName = "Enby"),
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
    val guildHalls: GuildHallsConfig = GuildHallsConfig(),
    val crafting: CraftingConfig = CraftingConfig(),
    val factions: FactionConfig = FactionConfig(),
    val currencies: CurrenciesConfig = CurrenciesConfig(),
    val pets: PetConfig = PetConfig(),
    val enchanting: EnchantingConfig = EnchantingConfig(),
    val bank: BankConfig = BankConfig(),
    val stylist: StylistConfig = StylistConfig(),
    val akathavae: AkathavaeConfig = AkathavaeConfig(),
    val worldTime: WorldTimeConfig = WorldTimeConfig(),
    val season: SeasonConfig = SeasonConfig(),
    val weather: WeatherConfig = WeatherConfig(),
    val worldEvents: WorldEventsConfig = WorldEventsConfig(),
    val mobVariants: MobVariantsConfig = MobVariantsConfig(),
    val environment: EnvironmentConfig = EnvironmentConfig(),
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
    val housing: HousingConfig = HousingConfig(),
    val characterCreation: CharacterCreationConfig = CharacterCreationConfig(),
    val commands: CommandsConfig = CommandsConfig(),
    val emotePresets: EmotePresetsConfig = EmotePresetsConfig(),
    /** Maps class name (e.g. "WARRIOR") to a fully-qualified RoomId string for new-character placement. */
    val classStartRooms: Map<String, String> = emptyMap(),
    /** How long to hold a disconnected player's session before full logout (ms). 0 disables. */
    val sessionResumeGracePeriodMs: Long = 600_000,
    val leaderboard: LeaderboardConfig = LeaderboardConfig(),
    val skillPoints: SkillPointsConfig = SkillPointsConfig(),
    val multiclass: MulticlassConfig = MulticlassConfig(),
    val respec: RespecConfig = RespecConfig(),
    val prestige: PrestigeConfig = PrestigeConfig(),
    val dailyQuests: DailyQuestsConfig = DailyQuestsConfig(),
    val autoQuests: AutoQuestsConfig = AutoQuestsConfig(),
    val globalQuests: GlobalQuestsConfig = GlobalQuestsConfig(),
    val lottery: LotteryConfig = LotteryConfig(),
    val gambling: GamblingConfig = GamblingConfig(),
    val jukebox: JukeboxConfig = JukeboxConfig(),
    val death: DeathConfig = DeathConfig(),
)

/**
 * Configuration for what happens when a player dies in PvE.
 *
 * On death, the player is moved to [sanctumRoom] (falling back to the zone's startRoom, then world
 * startRoom if unset/missing), restored to [respawnHpFraction] of max HP, and their last death
 * zone is recorded so a follow-up `depart` command can return them to that zone's start room.
 */
data class DeathConfig(
    /** Fully-qualified "zone:room" ID of the sanctum players respawn in. Null = zone startRoom fallback. */
    val sanctumRoom: String? = null,
    /** Fraction of maxHp restored on respawn (0.2 = 20%). Clamped to [0.05, 1.0]. */
    val respawnHpFraction: Double = 0.2,
    /** Fraction of maxMana restored on respawn (0.2 = 20%). Clamped to [0.0, 1.0]. */
    val respawnManaFraction: Double = 0.2,
    /** Fraction of current xpTotal deducted on death. 0 = no penalty. Clamped to [0.0, 0.5]. */
    val xpPenaltyFraction: Double = 0.0,
    val messages: DeathMessagesConfig = DeathMessagesConfig(),
)

data class DeathMessagesConfig(
    val arriveSanctum: String = "The world fades... and you awaken in the sanctum, your body mending.",
    val departNoSanctum: String = "You can only depart from the sanctum.",
    val departNoDeath: String = "You have nowhere to return to.",
    val departBegin: String = "You step through the spirit gate and return to the world.",
    val departUnreachable: String = "The spirit gate flickers, but nothing happens.",
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
    val restNotAtInn: String = "You can only rest at an inn.",
    val restSet: String = "You rest at {inn}. The innkeeper marks your name in the ledger — this inn is now your recall point.",
)

data class CommandMetadata(
    val usage: String = "",
    val description: String = "",
    val category: String = "general",
    val staff: Boolean = false,
    val requiresTarget: Boolean = false,
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
            "housing",
            "world",
            "social",
            "utility",
        )

        // Categories the ordered list doesn't know about are appended at the
        // end rather than silently dropped from help.
        val categories = orderedCategories.filter { it in grouped } +
            (grouped.keys - orderedCategories.toSet()).sorted()

        appendLine("Commands:")
        for (category in categories) {
            appendLine("  [${category.replaceFirstChar { it.uppercaseChar() }}]")
            for ((_, meta) in grouped.getValue(category)) {
                appendLine(formatHelpLine(meta))
            }
        }

        if (isStaff) {
            val staffCmds = entries.entries.filter { it.value.staff }
            if (staffCmds.isNotEmpty()) {
                appendLine("  [Staff] (requires staff flag)")
                for ((_, meta) in staffCmds) {
                    appendLine(formatHelpLine(meta))
                }
            }
        }
    }.trimEnd()

    private fun formatHelpLine(meta: CommandMetadata): String =
        if (meta.description.isEmpty()) "    ${meta.usage}" else "    ${meta.usage} — ${meta.description}"

    companion object {
        @Suppress("LongMethod")
        fun defaultCommandEntries(): Map<String, CommandMetadata> = linkedMapOf(
            "help" to CommandMetadata("help/?", "Show this help", "utility"),
            "look" to CommandMetadata("look/l [target|direction]", "Look around, at a target, or in a direction", "navigation"),
            "move" to CommandMetadata("n/s/e/w/u/d", "Move in a direction", "navigation"),
            "exits" to CommandMetadata("exits/ex", "List available exits", "navigation"),
            "recall" to CommandMetadata("recall", "Teleport to your recall point (set by resting at an inn)", "navigation"),
            "rest" to CommandMetadata("rest", "Rest at an inn to make it your recall point.", "navigation"),
            "depart" to CommandMetadata("depart", "Leave the death sanctum and return to the world", "navigation"),
            "run" to CommandMetadata(
                usage = "run <directions> (e.g. 5n3e)",
                description = "Move along a sequence of directions. Numeric prefixes repeat — '5n3e' walks five north then three east.",
                category = "navigation",
                requiresTarget = true,
            ),
            "areas" to CommandMetadata(
                usage = "areas [<minLevel> [<maxLevel>]]",
                description = "List known areas. With one level, shows areas covering that level; " +
                    "with two, shows areas overlapping the range.",
                category = "navigation",
            ),
            "say" to CommandMetadata("say <msg> or '<msg>", "Speak to the room", "communication", requiresTarget = true),
            "emote" to CommandMetadata("emote <msg>", "Perform an emote", "communication", requiresTarget = true),
            "pose" to CommandMetadata("pose <msg>", "Strike a pose", "communication", requiresTarget = true),
            "who" to CommandMetadata("who", "List online players", "communication"),
            "tell" to CommandMetadata("tell/t <player> <msg>", "Private message a player", "communication", requiresTarget = true),
            "whisper" to CommandMetadata("whisper/wh <player> <msg>", "Whisper to a player", "communication", requiresTarget = true),
            "gossip" to CommandMetadata("gossip/gs <msg>", "Global chat channel", "communication", requiresTarget = true),
            "shout" to CommandMetadata("shout/sh <msg>", "Shout to your zone", "communication", requiresTarget = true),
            "ooc" to CommandMetadata("ooc <msg>", "Out-of-character channel", "communication", requiresTarget = true),
            "inventory" to CommandMetadata("inventory/inv/i", "View your inventory", "items"),
            "equipment" to CommandMetadata("equipment/eq", "View worn equipment", "items"),
            "wear" to CommandMetadata("wear/equip <item>", "Equip an item", "items", requiresTarget = true),
            "remove" to CommandMetadata("remove/unequip <slot>", "Unequip from a slot", "items", requiresTarget = true),
            "get" to CommandMetadata("get/take/pickup <item>", "Pick up an item", "items", requiresTarget = true),
            "drop" to CommandMetadata("drop <item>", "Drop an item", "items", requiresTarget = true),
            "use" to CommandMetadata("use/eat/drink <item>", "Use a consumable item", "items", requiresTarget = true),
            "quickheal" to CommandMetadata("quickheal/qh", "Auto-use best healing potion", "combat"),
            "quickmana" to CommandMetadata("quickmana/qm", "Auto-use best mana potion", "combat"),
            "give" to CommandMetadata("give <item> <player>", "Give an item to a player", "items", requiresTarget = true),
            "talk" to CommandMetadata("talk <npc>", "Start a conversation with an NPC", "social", requiresTarget = true),
            "bye" to CommandMetadata("bye/goodbye", "End the current NPC conversation", "social"),
            "kill" to CommandMetadata("kill <mob>", "Attack a mob", "combat", requiresTarget = true),
            "flee" to CommandMetadata("flee", "Attempt to flee combat", "combat"),
            "cast" to CommandMetadata("cast/c <spell> [target]", "Cast a spell or ability", "combat", requiresTarget = true),
            "consider" to CommandMetadata(
                usage = "consider/con <mob>",
                description = "Estimate your odds against a mob before attacking",
                category = "combat",
                requiresTarget = true,
            ),
            "wimpy" to CommandMetadata("wimpy [off | 0-95]", "View or set the HP percent where you auto-flee combat", "combat"),
            "duel" to CommandMetadata(
                usage = "duel <player> | duel accept | duel decline",
                description = "Challenge another player to a PvP duel",
                category = "combat",
                requiresTarget = true,
            ),
            "spells" to CommandMetadata("spells/abilities/skills", "List your abilities", "progression"),
            "effects" to CommandMetadata("effects/buffs/debuffs", "View active status effects", "progression"),
            "score" to CommandMetadata("score/sc", "View your character sheet", "progression"),
            "balance" to CommandMetadata("gold/balance", "Check the gold you are carrying", "shops"),
            "currencies" to CommandMetadata("currencies/currency/wallet", "View secondary currencies", "progression"),
            "reputation" to CommandMetadata("reputation/rep/factions", "View your faction standings", "progression"),
            "shop_list" to CommandMetadata("list/shop", "Browse a shop's wares", "shops"),
            "buy" to CommandMetadata("buy <item>", "Purchase from a shop", "shops", requiresTarget = true),
            "sell" to CommandMetadata("sell <item>", "Sell to a shop", "shops", requiresTarget = true),
            "bank" to CommandMetadata("bank", "View bank balance and vault contents (requires a bank)", "shops"),
            "deposit" to CommandMetadata(
                usage = "deposit <amount|item>",
                description = "Deposit gold or an item into your bank vault",
                category = "shops",
                requiresTarget = true,
            ),
            "withdraw" to CommandMetadata(
                usage = "withdraw <amount|item>",
                description = "Withdraw gold or an item from your bank vault",
                category = "shops",
                requiresTarget = true,
            ),
            "auction" to CommandMetadata("auction", "Browse auction house listings", "shops"),
            "auction_sell" to CommandMetadata(
                usage = "auction sell <item> <price>",
                description = "List an item on the auction house",
                category = "shops",
                requiresTarget = true,
            ),
            "auction_buy" to CommandMetadata("auction buy <#>", "Buy an auction listing by number", "shops", requiresTarget = true),
            "auction_cancel" to CommandMetadata(
                usage = "auction cancel <#>",
                description = "Cancel your listing and reclaim the item",
                category = "shops",
                requiresTarget = true,
            ),
            "trade" to CommandMetadata(
                usage = "trade <player> | trade accept | trade cancel | trade status",
                description = "Trade items and gold with another player",
                category = "items",
                requiresTarget = true,
            ),
            "trade_offer" to CommandMetadata(
                usage = "trade offer <item> | trade offer <amount> gold",
                description = "Add an item or gold to the active trade",
                category = "items",
                requiresTarget = true,
            ),
            "trade_remove" to CommandMetadata(
                usage = "trade remove <item>",
                description = "Remove an item from your trade offer",
                category = "items",
                requiresTarget = true,
            ),
            "quest_log" to CommandMetadata("quest log/list", "View active quests", "quests"),
            "quest_info" to CommandMetadata("quest info <name>", "Quest details", "quests", requiresTarget = true),
            "quest_abandon" to CommandMetadata("quest abandon <name>", "Abandon a quest", "quests", requiresTarget = true),
            "quest_turnin" to CommandMetadata(
                "quest turnin <name>",
                "Turn in a completed quest to its giver NPC",
                "quests",
                requiresTarget = true,
            ),
            "accept" to CommandMetadata("accept <quest>", "Accept a quest from an NPC", "quests", requiresTarget = true),
            "bounty" to CommandMetadata("bounty / quest auto", "Request an auto-generated bounty quest", "quests"),
            "bounty_info" to CommandMetadata("bounty info / quest auto info", "View active bounty progress", "quests"),
            "bounty_abandon" to CommandMetadata("bounty abandon / quest auto abandon", "Abandon active bounty", "quests"),
            "achievements" to CommandMetadata("achievements/ach", "View achievements", "quests"),
            "daily" to CommandMetadata("daily/dailies", "View daily quest board", "quests"),
            "weekly" to CommandMetadata("weekly", "View weekly quest board", "quests"),
            "gquest" to CommandMetadata("gquest/gq/global", "View active global quest status", "quests"),
            "qoffers" to CommandMetadata("qoffers <mob>", "List the quests an NPC has to offer", "quests", requiresTarget = true),
            "group_invite" to CommandMetadata("group invite <player>", "Invite to your group", "groups", requiresTarget = true),
            "group_accept" to CommandMetadata("group accept", "Accept a group invite", "groups"),
            "group_leave" to CommandMetadata("group leave", "Leave your group", "groups"),
            "group_kick" to CommandMetadata("group kick <player>", "Kick from group", "groups", requiresTarget = true),
            "group_list" to CommandMetadata("group list (or just 'group')", "List group members", "groups"),
            "gtell" to CommandMetadata("gtell/gt <message>", "Group chat", "groups", requiresTarget = true),
            "guild_create" to CommandMetadata("guild create <name> <tag>", "Create a guild", "guilds", requiresTarget = true),
            "guild_disband" to CommandMetadata("guild disband", "Disband your guild", "guilds"),
            "guild_invite" to CommandMetadata("guild invite <player>", "Invite to guild", "guilds", requiresTarget = true),
            "guild_accept" to CommandMetadata("guild accept", "Accept a guild invite", "guilds"),
            "guild_leave" to CommandMetadata("guild leave", "Leave your guild", "guilds"),
            "guild_kick" to CommandMetadata("guild kick <player>", "Remove from guild", "guilds", requiresTarget = true),
            "guild_promote" to CommandMetadata("guild promote <player>", "Promote a member", "guilds", requiresTarget = true),
            "guild_demote" to CommandMetadata("guild demote <player>", "Demote a member", "guilds", requiresTarget = true),
            "guild_motd" to CommandMetadata("guild motd <message>", "Set guild message of the day", "guilds", requiresTarget = true),
            "guild_roster" to CommandMetadata("guild roster", "View guild members", "guilds"),
            "guild_info" to CommandMetadata("guild info (or just 'guild')", "Guild overview", "guilds"),
            "gchat" to CommandMetadata("gchat/g <message>", "Guild chat", "guilds", requiresTarget = true),
            "guild_hall" to CommandMetadata(
                usage = "guild hall [buy | expand <template> <direction> | enter | leave]",
                description = "View, buy, expand, and visit your guild hall",
                category = "guilds",
            ),
            "gather" to CommandMetadata("gather/harvest/mine <node>", "Gather from a resource node", "crafting", requiresTarget = true),
            "craft" to CommandMetadata("craft/make <recipe>", "Craft an item from a recipe", "crafting", requiresTarget = true),
            "recipes" to CommandMetadata("recipes [filter]", "Browse available recipes", "crafting"),
            "craftskills" to CommandMetadata("craftskills/professions", "View crafting skill levels", "crafting"),
            "specialize" to CommandMetadata("specialize/spec [profession]", "View or choose a crafting specialization", "crafting"),
            "enchant" to CommandMetadata(
                usage = "enchant <item> [enchantment]",
                description = "Enchant an item with a known enchantment",
                category = "crafting",
                requiresTarget = true,
            ),
            "enchantments" to CommandMetadata("enchantments", "List available enchantments", "crafting"),
            "house" to CommandMetadata("house [status]", "View your house info", "housing"),
            "house_list" to CommandMetadata("house list", "Browse available room templates", "housing"),
            "house_buy" to CommandMetadata("house buy", "Purchase your house (at broker)", "housing"),
            "house_expand" to CommandMetadata(
                "house expand <template> <direction>",
                "Add a room to your house",
                "housing",
                requiresTarget = true,
            ),
            "house_describe" to CommandMetadata(
                "house describe [title|desc] <text>",
                "Customize room title or description",
                "housing",
                requiresTarget = true,
            ),
            "house_invite" to CommandMetadata(
                "house invite <player>",
                "Invite a player to your house",
                "housing",
                requiresTarget = true,
            ),
            "house_kick" to CommandMetadata(
                "house kick <player>",
                "Remove a visitor from your house",
                "housing",
                requiresTarget = true,
            ),
            "house_guests" to CommandMetadata("house guests", "List visitors in your house", "housing"),
            "open" to CommandMetadata("open <door|container>", "Open a door or container", "world", requiresTarget = true),
            "close" to CommandMetadata("close <door|container>", "Close a door or container", "world", requiresTarget = true),
            "unlock" to CommandMetadata("unlock <door|container>", "Unlock with a key", "world", requiresTarget = true),
            "lock" to CommandMetadata("lock <door|container>", "Lock with a key", "world", requiresTarget = true),
            "search" to CommandMetadata("search <container>", "Search a container for its contents", "world", requiresTarget = true),
            "get_from" to CommandMetadata("get <item> from <container>", "Take an item from a container", "world", requiresTarget = true),
            "put_in" to CommandMetadata("put <item> <container>", "Place an item in a container", "world", requiresTarget = true),
            "pull" to CommandMetadata("pull <lever>", "Pull a lever or object", "world", requiresTarget = true),
            "read" to CommandMetadata("read <sign>", "Read a sign or inscription", "world", requiresTarget = true),
            "answer" to CommandMetadata("answer <text>", "Answer a riddle in the room", "world", requiresTarget = true),
            "dungeon" to CommandMetadata(
                usage = "dungeon enter <name> [difficulty] | dungeon leave",
                description = "Enter or leave an instanced dungeon",
                category = "world",
                requiresTarget = true,
            ),
            "title" to CommandMetadata("title <titleName> | title clear", "Set or clear your title", "progression", requiresTarget = true),
            "gender" to CommandMetadata("gender <option>", "Set your gender", "progression", requiresTarget = true),
            "sprite" to CommandMetadata("sprite list | set <id> | default", "Manage your character sprite", "progression"),
            "describe" to CommandMetadata(
                usage = "describe <text> | describe clear | describe check <player>",
                description = "Set, clear, or view a character description",
                category = "progression",
                requiresTarget = true,
            ),
            "train" to CommandMetadata(
                usage = "train [list] | train learn <ability> | train unlock <class> | train reset",
                description = "Learn abilities, unlock classes, or respec at a class trainer",
                category = "progression",
            ),
            "prestige" to CommandMetadata("prestige | prestige info", "Reset at max level for permanent prestige perks", "progression"),
            "leaderboard" to CommandMetadata("leaderboard/top [category]", "View player leaderboards", "progression"),
            "stylist" to CommandMetadata("stylist", "View race-change options and fee (requires a stylist)", "progression"),
            "pledge" to CommandMetadata(
                usage = "pledge",
                description = "Take the Akathavae pledge at a shrine — forsake combat, level through illumination",
                category = "progression",
            ),
            "renounce" to CommandMetadata(
                usage = "renounce [confirm]",
                description = "Renounce the Akathavae pledge at a shrine (costs gold)",
                category = "progression",
            ),
            "illuminate" to CommandMetadata(
                usage = "illuminate <creature>",
                description = "Record a creature in your Arcanum — the Akathavae's replacement for attacking",
                category = "progression",
                requiresTarget = true,
            ),
            "arcanum" to CommandMetadata(
                usage = "arcanum [rooms|mobs|items]",
                description = "Leaf through your Arcanum journal of recorded places, creatures, and items",
                category = "progression",
            ),
            "wardrobe" to CommandMetadata(
                usage = "wardrobe [item]",
                description = "Conjure and wear equipment recorded in your Arcanum (Akathavae only)",
                category = "progression",
            ),
            "changerace" to CommandMetadata(
                usage = "changerace <race>",
                description = "Pay the stylist to change your race",
                category = "progression",
                requiresTarget = true,
            ),
            "pet" to CommandMetadata(
                usage = "pet [status] | pet name <name> | pet skills | pet <skill> | pet dismiss",
                description = "Manage your pet and trigger its skills",
                category = "progression",
            ),
            "friend" to CommandMetadata("friend list | add <player> | remove <player>", "Manage your friends list", "social"),
            "mail" to CommandMetadata("mail list | read <n> | send <player> | delete <n>", "Manage mail", "social"),
            "lottery" to CommandMetadata(
                usage = "lottery [info] | lottery buy [count]",
                description = "View the lottery or buy tickets (buying requires a tavern)",
                category = "social",
            ),
            "gamble" to CommandMetadata("gamble/dice <amount>", "Roll d100 against the house (requires a tavern)", "social"),
            "jukebox" to CommandMetadata(
                usage = "jukebox [list] | jukebox play <number> | jukebox queue <number>",
                description = "List the room's jukebox songs, pay to play one, or queue the next track",
                category = "social",
            ),
            "musicbox" to CommandMetadata(
                usage = "musicbox [list] | musicbox play | musicbox stop",
                description = "Open the room's music box and wind up its one song — free, and it follows you until it ends",
                category = "social",
            ),
            "ansi" to CommandMetadata("ansi on/off", "Toggle color output", "utility"),
            "screenreader" to CommandMetadata("screenreader [on/off]", "Toggle screen reader mode", "utility"),
            "audio" to CommandMetadata(
                usage = "audio [on/off]",
                description = "Print music, ambient, and NPC voice URLs inline (for non-web clients)",
                category = "utility",
            ),
            "autoloot" to CommandMetadata(
                usage = "autoloot on/off/status",
                description = "Auto-loot mob corpses when enabled",
                category = "utility",
            ),
            "autopeek" to CommandMetadata(
                usage = "autopeek on/off/status",
                description = "Append adjacent room names below room descriptions",
                category = "utility",
            ),
            "colors" to CommandMetadata("colors", "Preview ANSI color palette", "utility"),
            "clear" to CommandMetadata("clear", "Clear the terminal", "utility"),
            "quit" to CommandMetadata("quit/exit", "Disconnect", "utility"),
            "phase" to CommandMetadata("phase/layer [instance]", "Switch zone instance", "utility"),
            "time" to CommandMetadata("time", "Show the in-game time of day", "utility"),
            "claim" to CommandMetadata(
                usage = "claim <password> | claim <newname> <password>",
                description = "Save a demo character as a permanent account",
                category = "utility",
            ),
            // Staff commands
            "goto" to CommandMetadata(
                usage = "goto <zone:room | room | zone:>",
                description = "Teleport to a room",
                category = "admin",
                staff = true,
                requiresTarget = true,
            ),
            "transfer" to CommandMetadata(
                usage = "transfer <player> <room>",
                description = "Move a player",
                category = "admin",
                staff = true,
                requiresTarget = true,
            ),
            "spawn" to CommandMetadata(
                usage = "spawn <mob-template>",
                description = "Spawn a mob",
                category = "admin",
                staff = true,
                requiresTarget = true,
            ),
            "smite" to CommandMetadata(
                usage = "smite <player|mob>",
                description = "Instantly kill a target",
                category = "admin",
                staff = true,
                requiresTarget = true,
            ),
            "staff_kick" to CommandMetadata(
                usage = "kick <player>",
                description = "Disconnect a player",
                category = "admin",
                staff = true,
                requiresTarget = true,
            ),
            "dispel" to CommandMetadata(
                usage = "dispel <player|mob>",
                description = "Remove all effects",
                category = "admin",
                staff = true,
                requiresTarget = true,
            ),
            "setlevel" to CommandMetadata(
                usage = "setlevel <player> <level>",
                description = "Set a player's level",
                category = "admin",
                staff = true,
                requiresTarget = true,
            ),
            "shutdown" to CommandMetadata("shutdown", "Shut down the server", "admin", staff = true),
            "reload" to CommandMetadata("reload [scope]", "Reload world data", "admin", staff = true),
            "possess" to CommandMetadata(
                usage = "possess/switch <mob>",
                description = "Take control of a mob",
                category = "admin",
                staff = true,
                requiresTarget = true,
            ),
            "return" to CommandMetadata("return/unpossess", "Release a possessed mob", "admin", staff = true),
            "invis" to CommandMetadata("invis", "Toggle staff invisibility", "admin", staff = true),
            "broadcast" to CommandMetadata(
                usage = "broadcast <message>",
                description = "Send a server-wide announcement",
                category = "admin",
                staff = true,
                requiresTarget = true,
            ),
            "heal" to CommandMetadata("heal [player]", "Fully restore a player's HP and mana", "admin", staff = true),
            "pinfo" to CommandMetadata("pinfo <player>", "Inspect a player's state", "admin", staff = true, requiresTarget = true),
            "setstaff" to CommandMetadata(
                usage = "setstaff/grantstaff <player> | revokestaff <player>",
                description = "Grant or revoke staff access",
                category = "admin",
                staff = true,
                requiresTarget = true,
            ),
            "setgold" to CommandMetadata(
                usage = "setgold <player> <amount>",
                description = "Set a player's gold",
                category = "admin",
                staff = true,
                requiresTarget = true,
            ),
            "setrace" to CommandMetadata(
                usage = "setrace <player> <race>",
                description = "Set a player's race",
                category = "admin",
                staff = true,
                requiresTarget = true,
            ),
            "setclass" to CommandMetadata(
                usage = "setclass <player> <class>",
                description = "Set a player's class",
                category = "admin",
                staff = true,
                requiresTarget = true,
            ),
            "setgender" to CommandMetadata(
                usage = "setgender <player> <gender>",
                description = "Set a player's gender",
                category = "admin",
                staff = true,
                requiresTarget = true,
            ),
            "setxp" to CommandMetadata(
                usage = "setxp <player> <xp>",
                description = "Set a player's XP",
                category = "admin",
                staff = true,
                requiresTarget = true,
            ),
        )
    }
}

data class EngineDebugConfig(
    val enableSwarmClass: Boolean = false,
)

data class ClassDefinitionConfig(
    val displayName: String = "",
    val hpScalingRate: Double = 1.0,
    val manaScalingRate: Double = 1.0,
    val description: String = "",
    val backstory: String = "",
    val image: String = "",
    val selectable: Boolean = true,
    val primaryStat: String = "",
    val statPriorities: List<String> = emptyList(),
    val startRoom: String = "",
    val threatMultiplier: Double = 1.0,
    val starterEquipment: List<StarterEquipmentEntryConfig> = emptyList(),
)

data class StarterEquipmentEntryConfig(
    val itemId: String = "",
    val equip: Boolean = true,
)

data class ClassEngineConfig(
    val definitions: Map<String, ClassDefinitionConfig> = emptyMap(),
)

data class RaceDefinitionConfig(
    val displayName: String = "",
    val description: String = "",
    val backstory: String = "",
    val traits: List<String> = emptyList(),
    val abilities: List<String> = emptyList(),
    val image: String = "",
    val statMods: Map<String, Int> = emptyMap(),
    /** Optional race-specific passive ability (low-health / lethal-blow trigger). */
    val racialAbility: RacialAbilityConfig? = null,
)

/**
 * Tunable knobs for a race's passive ability. [kind] selects the mechanic (must match a
 * `RacialAbilityKind` name); the remaining fields are read only by the kinds that use them.
 */
data class RacialAbilityConfig(
    val kind: String = "",
    val displayName: String = "",
    val cooldownMs: Long = 120_000L,
    val triggerHealthPct: Int = 0,
    val aoeDamagePctOfMaxHp: Double = 0.0,
    val damageMultiplier: Double = 1.0,
    val buffDurationMs: Long = 0L,
    val stunStatusId: String? = null,
    val petTemplateKey: String? = null,
    val petCountMin: Int = 1,
    val petCountMax: Int = 1,
    val petDurationMs: Long = 0L,
    val regenPctOfMaxHp: Double = 0.0,
    val stoneStatusId: String? = null,
    val stoneDurationMs: Long = 0L,
    val phaseTicks: Int = 2,
    val selfMessage: String = "",
    val roomMessage: String = "",
)

data class RaceEngineConfig(
    val definitions: Map<String, RaceDefinitionConfig> = emptyMap(),
)

data class StatDefinitionConfig(
    val displayName: String = "",
    val abbreviation: String = "",
    val description: String = "",
    val baseStat: Int = 10,
)

data class StatBindingsConfig(
    val meleeDamageStat: String = "STR",
    /**
     * Multiplicative bonus per stat point above [StatDefinitionConfig.baseStat]
     * for basic melee attacks. The bonus is added to attackPower *before* level
     * scaling, so it compounds with level — keep the multiplier modest so stat
     * allocation doesn't dominate gear at high levels. At 0.25 a player with
     * STR 14 gains +1 to the core, which then scales by levelScalingRate.
     */
    val meleeStatMultiplier: Double = 0.25,
    /**
     * Per-level multiplicative growth applied to the (attackPower + statBonus)
     * core of a basic melee swing. Mirrors `progression.rewards.hpScalingRate`
     * so player damage tracks player HP across the level curve.
     */
    val meleeLevelScalingRate: Double = 1.30,
    /** Lower bound of the multiplicative variance roll applied to the core. */
    val meleeVarianceMin: Double = 0.85,
    /** Upper bound of the multiplicative variance roll applied to the core. */
    val meleeVarianceMax: Double = 1.15,
    /**
     * Baseline attack power every player always contributes (fist swing on top
     * of any weapon). Final attackPower is `meleeBaseAttackPower + equipmentAttack`,
     * so a low-damage weapon is still strictly better than fists.
     */
    val meleeBaseAttackPower: Int = 1,
    /**
     * Half-mitigation constant for the multiplicative armor formula:
     *   mitigation = armor / (armor + meleeArmorMitigationK)
     *   final     = round(raw * (1 - mitigation))
     *
     * At K=20, armor 5 ≈ 20% reduction, armor 20 ≈ 50%. Self-scaling: armor
     * matters the same proportion whether raw damage is 5 or 50,000, which
     * fixes the "flat armor evaporates at high level" problem of subtractive
     * mitigation. Applied symmetrically to both player→mob and mob→player.
     */
    val meleeArmorMitigationK: Double = 20.0,
    val dodgeStat: String = "DEX",
    val dodgePerPoint: Int = 2,
    val maxDodgePercent: Int = 30,
    val spellDamageStat: String = "INT",
    /**
     * Spell damage uses the same shape as basic melee — ability-authored damage
     * range provides the anchor, then statBonus + levelScale + variance apply:
     *
     *   anchor      = (effect.damage.min + effect.damage.max) / 2
     *   statBonus   = (stat - basePoint) × spellStatMultiplier
     *   levelScale  = spellLevelScalingRate ^ (level - 1)
     *   core        = (anchor + statBonus) × levelScale
     *   final       = round(core × uniform(spellVarianceMin, spellVarianceMax))
     *
     * Spells bypass armor — physical mitigation doesn't apply to magical hits.
     */
    val spellStatMultiplier: Double = 0.25,
    val spellLevelScalingRate: Double = 1.30,
    val spellVarianceMin: Double = 0.85,
    val spellVarianceMax: Double = 1.15,
    /**
     * Bindings for direct/area heals — same shape as spell damage, scaled by
     * [healStat] (WIS by default). Replaces the per-ability `healPerLevel`
     * additive scaling with a global compounding rate.
     */
    val healStat: String = "WIS",
    val healStatMultiplier: Double = 0.25,
    val healLevelScalingRate: Double = 1.30,
    val healVarianceMin: Double = 0.85,
    val healVarianceMax: Double = 1.15,
    /**
     * Bindings for buff-style abilities (`AbilityEffect.ApplyStatus` targeting
     * self/ally/group). [buffStat] (CHA by default) drives duration and magnitude
     * scaling for utility/support classes (bard, warlord, herald):
     *
     *   durationMultiplier  = 1.0 + (stat - basePoint) × buffDurationPerStat
     *   magnitudeMultiplier = 1.0 + (stat - basePoint) × buffMagnitudePerStat
     *
     * Wiring into the actual status-effect engine is a follow-up — these knobs
     * are reserved so support-class abilities have a defined scaling lane the
     * same way damage scales off INT and heals scale off WIS.
     */
    val buffStat: String = "CHA",
    val buffDurationPerStat: Double = 0.02,
    val buffMagnitudePerStat: Double = 0.02,
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
    val quests: QuestXpConfig = QuestXpConfig(),
)

/**
 * Engine-computed XP for quests. Quests with an explicit `rewards.xp` authored
 * in the YAML keep that value as an override; quests that declare only a
 * `difficulty` (and optional `level`) get
 *   `(baseline.baseXp + baseline.xpPerLevel * (level - 1)) * tiers[difficulty]`
 * at completion time, with diminishing returns layered on top as usual.
 */
data class QuestXpConfig(
    val baseline: QuestBaselineConfig = QuestBaselineConfig(),
    val tiers: Map<QuestDifficulty, Double> =
        mapOf(
            QuestDifficulty.TRIVIAL to 0.25,
            QuestDifficulty.EASY to 0.5,
            QuestDifficulty.STANDARD to 1.0,
            QuestDifficulty.HARD to 1.75,
            QuestDifficulty.EPIC to 3.0,
        ),
)

data class QuestBaselineConfig(
    val baseXp: Long = 50L,
    val xpPerLevel: Long = 20L,
)

enum class QuestDifficulty {
    TRIVIAL,
    EASY,
    STANDARD,
    HARD,
    EPIC,
    ;

    companion object {
        /**
         * Parses a difficulty string from world YAML. Null/blank returns null
         * (quest has no tier — XP comes from the authored `rewards.xp` alone).
         */
        fun parse(raw: String?): QuestDifficulty? {
            if (raw.isNullOrBlank()) return null
            val normalized = raw.trim().uppercase()
            return entries.firstOrNull { it.name == normalized }
                ?: throw IllegalArgumentException(
                    "Unknown quest difficulty '$raw' (expected one of ${entries.joinToString { it.name.lowercase() }})",
                )
        }
    }
}

data class XpCurveConfig(
    val baseXp: Long = 100L,
    val exponent: Double = 2.2,
    val linearXp: Long = 150L,
    val multiplier: Double = 1.0,
    val defaultKillXp: Long = 50L,
    val diminishing: DiminishingXpConfig = DiminishingXpConfig(),
)

/**
 * Diminishing returns applied when a player has out-levelled the content
 * awarding XP. Used by kills (mob level) and by quests/puzzles that declare
 * an intended player level. The highest matching `levelsBelow` wins.
 */
data class DiminishingXpConfig(
    val enabled: Boolean = true,
    val thresholds: List<DiminishingXpThreshold> =
        listOf(
            DiminishingXpThreshold(levelsBelow = 3, multiplier = 0.5),
            DiminishingXpThreshold(levelsBelow = 5, multiplier = 0.2),
            DiminishingXpThreshold(levelsBelow = 8, multiplier = 0.0),
        ),
)

data class DiminishingXpThreshold(
    val levelsBelow: Int = 0,
    val multiplier: Double = 1.0,
)

data class LevelRewardsConfig(
    val hpScalingRate: Double = 1.0,
    val manaScalingRate: Double = 1.0,
    val fullHealOnLevelUp: Boolean = true,
    val fullManaOnLevelUp: Boolean = true,
    val baseHp: Int = 10,
    val baseMana: Int = 20,
)

data class MobTierConfig(
    val baseHp: Int = 10,
    val hpScalingRate: Double = 1.0,
    val baseMinDamage: Int = 1,
    val baseMaxDamage: Int = 4,
    val damageScalingRate: Double = 1.0,
    val baseArmor: Int = 0,
    val baseXpReward: Long = 30L,
    val xpScalingRate: Double = 1.0,
    val baseGoldMin: Long = 0L,
    val baseGoldMax: Long = 0L,
    val goldScalingRate: Double = 1.0,
)

data class MobTiersConfig(
    val weak: MobTierConfig =
        MobTierConfig(
            baseHp = 5,
            hpScalingRate = 1.10,
            baseMinDamage = 1,
            baseMaxDamage = 2,
            damageScalingRate = 1.06,
            baseArmor = 0,
            baseXpReward = 15L,
            xpScalingRate = 1.09,
            baseGoldMin = 1L,
            baseGoldMax = 3L,
            goldScalingRate = 1.19,
        ),
    val standard: MobTierConfig =
        MobTierConfig(
            baseHp = 10,
            hpScalingRate = 1.10,
            baseMinDamage = 1,
            baseMaxDamage = 4,
            damageScalingRate = 1.07,
            baseArmor = 0,
            baseXpReward = 30L,
            xpScalingRate = 1.08,
            baseGoldMin = 2L,
            baseGoldMax = 8L,
            goldScalingRate = 1.19,
        ),
    val elite: MobTierConfig =
        MobTierConfig(
            baseHp = 20,
            hpScalingRate = 1.09,
            baseMinDamage = 2,
            baseMaxDamage = 6,
            damageScalingRate = 1.07,
            baseArmor = 1,
            baseXpReward = 75L,
            xpScalingRate = 1.08,
            baseGoldMin = 10L,
            baseGoldMax = 25L,
            goldScalingRate = 1.19,
        ),
    val boss: MobTierConfig =
        MobTierConfig(
            baseHp = 50,
            hpScalingRate = 1.09,
            baseMinDamage = 3,
            baseMaxDamage = 8,
            damageScalingRate = 1.07,
            baseArmor = 3,
            baseXpReward = 200L,
            xpScalingRate = 1.07,
            baseGoldMin = 50L,
            baseGoldMax = 100L,
            goldScalingRate = 1.19,
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
    val feedback: CombatFeedbackConfig = CombatFeedbackConfig(),
)

data class CombatFeedbackConfig(
    val enabled: Boolean = false,
    val roomBroadcastEnabled: Boolean = false,
)

data class RegenEngineConfig(
    val cycleTargetMillis: Long = 2_000L,
    val minPlayersPerTick: Int = 5,
    val maxPlayersPerTick: Int = 200,
    val baseIntervalMillis: Long = 5_000L,
    val minIntervalMillis: Long = 1_000L,
    val regenPercent: Double = 0.05,
    val inCombatMultiplier: Double = 0.5,
    /** Regen multiplier while resting in a room flagged as an inn (HP + mana). */
    val innMultiplier: Double = 2.0,
    val mana: ManaRegenConfig = ManaRegenConfig(),
)

data class ManaRegenConfig(
    val baseIntervalMillis: Long = 3_000L,
    val minIntervalMillis: Long = 1_000L,
    val regenPercent: Double = 0.05,
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

data class GuildHallsConfig(
    /** Master toggle for the guild halls feature. */
    val enabled: Boolean = true,
    /** Gold cost for the initial guild hall purchase (creates meeting_hall). */
    val purchaseCost: Long = 50_000L,
    /** Gold cost per additional room expansion. */
    val roomCost: Long = 10_000L,
    /** Maximum number of rooms a guild hall can contain. */
    val maxRooms: Int = 10,
    /** Room template definitions keyed by template id. */
    val templates: Map<String, GuildHallTemplateConfig> = emptyMap(),
)

data class GuildHallTemplateConfig(
    val title: String = "",
    val description: String = "",
    /** When true, the vault storage feature is enabled for this room. */
    val hasStorage: Boolean = false,
)

data class FriendsConfig(
    val maxFriends: Int = 50,
)

data class HousingConfig(
    /** Master toggle for the housing system. */
    val enabled: Boolean = true,
    /** Direction in the entry room that leads back to the world. */
    val entryExitDirection: Direction = Direction.SOUTH,
    /** Room template definitions keyed by template id. */
    val templates: Map<String, RoomTemplateConfig> = emptyMap(),
)

data class RoomTemplateConfig(
    val title: String = "",
    val description: String = "",
    val cost: Long = 0L,
    val isEntry: Boolean = false,
    val image: String? = null,
    /** When > 0, items dropped here persist across sessions (vault room). */
    val maxDroppedItems: Int = 0,
    /** When true, combat cannot be initiated in this room. */
    val safe: Boolean = false,
    /** Optional crafting station type (e.g. "forge", "alchemy_bench"). */
    val station: String? = null,
)

data class AbilityEngineConfig(
    val definitions: Map<String, AbilityDefinitionConfig> = emptyMap(),
)

data class AbilityDefinitionConfig(
    val displayName: String = "",
    val description: String = "",
    /**
     * Mana cost as a percentage (0-100+) of the player's level/class base mana
     * pool (computed with default INT). Absolute cost is resolved per-cast —
     * see [dev.ambon.engine.abilities.AbilityDefinition.manaCostPct] for the
     * design rationale.
     */
    val manaCostPct: Double = 10.0,
    val cooldownMs: Long = 0L,
    val levelRequired: Int = 1,
    val skillPointCost: Int = 1,
    val targetType: String = "ENEMY",
    val effect: AbilityEffectConfig = AbilityEffectConfig(),
    val requiredClass: String = "",
    val image: String = "",
    val prerequisites: List<String> = emptyList(),
    val tree: String = "",
    val tier: Int = 0,
    val visual: AbilityVisualConfig = AbilityVisualConfig(),
) {
    init {
        require(skillPointCost >= 0) { "skillPointCost must be >= 0, got $skillPointCost" }
    }
}

data class AbilityVisualConfig(
    val archetype: String = "",
    val projectileImage: String = "",
    val color: String = "",
    val accentColor: String = "",
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
    val petTemplateKey: String = "",
    val durationMs: Long = 0L,
    /**
     * Child effects used when [type] is `COMPOSITE`. The ability pays its
     * mana/cooldown once and every child resolves against the same target.
     * Ignored for non-composite types.
     */
    val effects: List<AbilityEffectConfig> = emptyList(),
)

data class SkillPointsConfig(
    /** Player gains 1 skill point every this many levels. Must be >= 1. */
    val interval: Int = 2,
) {
    init {
        require(interval >= 1) { "skillPoints.interval must be >= 1, got $interval" }
    }
}

data class RespecConfig(
    /** Whether the respec system is enabled. */
    val enabled: Boolean = true,
    /** Gold cost to reset all learned abilities. Must be >= 0. */
    val goldCost: Long = 1000L,
    /** Cooldown between respecs in milliseconds. 0 disables cooldown. */
    val cooldownMs: Long = 3_600_000L,
) {
    init {
        require(goldCost >= 0) { "respec.goldCost must be >= 0, got $goldCost" }
        require(cooldownMs >= 0) { "respec.cooldownMs must be >= 0, got $cooldownMs" }
    }
}

data class MulticlassConfig(
    /** Minimum player level required to unlock an additional class. */
    val minLevel: Int = 10,
    /** Base gold cost to unlock a new class at a trainer (charged for the first trainer unlock). */
    val goldCost: Long = 500L,
    /**
     * Maximum number of classes a player may have unlocked (including their starter class).
     * Defaults to effectively unlimited so existing deployments keep working unchanged;
     * curated `application.yaml` ships a tighter cap.
     *
     * Typed as [Long] so config overlays written by JavaScript tooling (which often uses
     * `Number.MAX_SAFE_INTEGER` = 2^53−1 as an "unlimited" sentinel) decode without overflow.
     */
    val maxClasses: Long = Long.MAX_VALUE,
    /**
     * Exponential multiplier applied per additional class beyond the first trainer unlock.
     * Cost for the Nth trainer unlock is `goldCost * goldCostMultiplier^(N-1)`, so the first
     * trainer unlock costs `goldCost`, the second `goldCost * multiplier`, and so on. Default
     * 1.0 keeps the cost flat (no-op).
     */
    val goldCostMultiplier: Double = 1.0,
) {
    init {
        require(minLevel >= 1) { "multiclass.minLevel must be >= 1, got $minLevel" }
        require(goldCost >= 0L) { "multiclass.goldCost must be >= 0, got $goldCost" }
        require(maxClasses >= 1) { "multiclass.maxClasses must be >= 1, got $maxClasses" }
        require(goldCostMultiplier >= 1.0) {
            "multiclass.goldCostMultiplier must be >= 1.0, got $goldCostMultiplier"
        }
    }

    /**
     * Gold required for the next trainer unlock given the player's current unlocked-class
     * count (which always includes the starter class, so the first trainer unlock passes
     * `currentlyUnlocked = 1`). Saturates at [Long.MAX_VALUE] for absurd configurations.
     */
    fun costFor(currentlyUnlocked: Int): Long {
        val priorTrainerUnlocks = (currentlyUnlocked - 1).coerceAtLeast(0)
        if (priorTrainerUnlocks == 0 || goldCostMultiplier == 1.0) return goldCost
        val scaled = goldCost.toDouble() * Math.pow(goldCostMultiplier, priorTrainerUnlocks.toDouble())
        return if (scaled >= Long.MAX_VALUE.toDouble()) Long.MAX_VALUE else scaled.toLong()
    }
}

data class GlobalQuestObjectiveConfig(
    /** Objective type: "kill", "gather", or "craft". */
    val type: String = "kill",
    /** Number of actions required to complete the objective. */
    val targetCount: Int = 25,
    /** Human-readable description shown to players. */
    val description: String = "",
)

data class GlobalQuestsConfig(
    /** Whether global competitive quests are enabled. */
    val enabled: Boolean = true,
    /** Interval between quests in milliseconds (default 2 hours). */
    val intervalMs: Long = 7_200_000L,
    /** Duration of each quest in milliseconds (default 30 minutes). */
    val durationMs: Long = 1_800_000L,
    /** Interval between progress announcements in milliseconds (default 5 minutes). */
    val announceIntervalMs: Long = 300_000L,
    /** Minimum number of online players required to start a quest. */
    val minPlayersOnline: Int = 2,
    /** Gold reward for 1st place. */
    val rewardGoldFirst: Long = 2000L,
    /** Gold reward for 2nd place. */
    val rewardGoldSecond: Long = 1000L,
    /** Gold reward for 3rd place. */
    val rewardGoldThird: Long = 500L,
    /** XP reward for 1st place. */
    val rewardXpFirst: Long = 5000L,
    /** XP reward for 2nd place. */
    val rewardXpSecond: Long = 2500L,
    /** XP reward for 3rd place. */
    val rewardXpThird: Long = 1000L,
    /** Available objective templates; one is chosen at random when a quest starts. */
    val objectives: List<GlobalQuestObjectiveConfig> = listOf(
        GlobalQuestObjectiveConfig(type = "kill", targetCount = 25, description = "Slay 25 creatures"),
        GlobalQuestObjectiveConfig(type = "kill", targetCount = 50, description = "Slay 50 creatures"),
        GlobalQuestObjectiveConfig(type = "gather", targetCount = 15, description = "Gather 15 resources"),
        GlobalQuestObjectiveConfig(type = "craft", targetCount = 10, description = "Craft 10 items"),
    ),
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
    /** Maximum number of concurrent telnet connections before new connections are rejected. */
    val maxConnections: Int = 5000,
)

data class WebSocketTransportConfig(
    val host: String = "0.0.0.0",
    val stopGraceMillis: Long = 1_000L,
    val stopTimeoutMillis: Long = 2_000L,
    /** Maximum number of concurrent WebSocket connections before new connections are rejected. */
    val maxConnections: Int = 5000,
    /** Maximum concurrent WebSocket connections from a single remote IP (0 disables the per-IP cap). */
    val maxConnectionsPerIp: Int = 30,
    /** Server→client ping interval in ms; also drives dead-peer detection (0 disables pings). */
    val pingPeriodMillis: Long = 15_000L,
    /** Time to wait for a pong before closing the connection, in ms. Defends against slow-loris holds. */
    val pongTimeoutMillis: Long = 30_000L,
    /** Maximum inbound WebSocket frame size in bytes. Frames larger than this are rejected. */
    val maxFrameBytes: Long = 65_536L,
)

data class DemoConfig(
    val autoLaunchBrowser: Boolean = false,
    val webClientHost: String = "localhost",
    val webClientUrl: String? = null,
)

data class ObservabilityConfig(
    val metricsEnabled: Boolean = true,
    val metricsEndpoint: String = "/metrics",
    val metricsHttpPort: Int = 9099,
    /** Bind address for the metrics HTTP listener. Default is 0.0.0.0 (all interfaces). */
    val metricsHttpHost: String = "0.0.0.0",
    val staticTags: Map<String, String> = emptyMap(),
)

data class AdminConfig(
    /** Enable the admin HTTP dashboard. Requires a non-blank [token]. */
    val enabled: Boolean = false,
    /**
     * Bind address for the admin dashboard. Defaults to loopback so the privileged API (grant/revoke
     * staff, broadcast, hot-reload, full player PII) is not reachable from the network unless an
     * operator deliberately exposes it. Use a reverse proxy or set to `0.0.0.0` to expose it.
     */
    val host: String = "127.0.0.1",
    /** Port the admin dashboard listens on. */
    val port: Int = 9091,
    /** Bearer/Basic-auth password required for every admin request. */
    val token: String = "",
    /** Optional Grafana dashboard URL shown as a link on the overview page. */
    val grafanaUrl: String = "",
    /** Allowed CORS origins for external tools (e.g. Arcanum). Empty list disables CORS. */
    val corsOrigins: List<String> = emptyList(),
    /** Base path for HTML links when served behind a reverse proxy (e.g. "/admin/"). Must end with "/". */
    val basePath: String = "/",
)

data class LoggingConfig(
    val level: String = "INFO",
    val packageLevels: Map<String, String> = emptyMap(),
)

data class GrpcServerConfig(
    val port: Int = 9090,
    /** Control-plane send timeout in ms. Increase for WAN/VPN scenarios. */
    val controlPlaneSendTimeoutMs: Long = 2_000L,
)

data class GrpcClientConfig(
    val engineHost: String = "localhost",
    val enginePort: Int = 9090,
)

data class GrpcConfig(
    val server: GrpcServerConfig = GrpcServerConfig(),
    val client: GrpcClientConfig = GrpcClientConfig(),
    /** Shared secret for HMAC-based gRPC authentication between engine and gateway. */
    val sharedSecret: String = "",
    /** Allow plaintext gRPC transport (no TLS). Auth interceptor still applies when true. */
    val allowPlaintext: Boolean = true,
    /** Maximum clock skew tolerance in milliseconds for HMAC timestamp validation. */
    val timestampToleranceMs: Long = 30_000L,
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
) {
    companion object {
        val DEFAULT_GLOBAL_ASSETS: Map<String, String> = linkedMapOf(
            // Painted full-screen login flow scenes. The web client maps the live
            // controls (inputs, buttons, name chips, list/detail regions) onto the
            // painted elements; absent/404 → the CSS-only login UI renders instead.
            // Emitted pre-auth from LoginFlowHandler.promptForName so the login
            // screen can use them before authentication.
            "login_bg" to "global_assets/login_bg.png", // name entry + start demo (1536×1024)
            "login_password_bg" to "global_assets/login_password_bg.png", // returning-user password (1448×1086)
            "login_set_password_bg" to "global_assets/login_set_password_bg.png", // new password (1536×1024)
            "login_confirm_bg" to "global_assets/login_confirm_bg.png", // create-character confirm (1448×1086)
            "login_picker_bg" to "global_assets/login_picker_bg.png", // saved-character picker (1448×1086)
            "login_race_bg" to "global_assets/login_race_bg.png", // race selection, 9 slots (1122×1402 portrait)
            "login_class_bg" to "global_assets/login_class_bg.png", // class selection, 6 slots (1122×1402 portrait)
            "login_claim_bg" to "global_assets/login_claim_bg.png", // demo claim / Save Your Character (1448×1086)
            // Phone-portrait companions (941×1672) for the landscape scenes above.
            // The client prefers these on portrait viewports; race/class are
            // already portrait and have no companion.
            "login_bg_portrait" to "global_assets/login_bg_portrait.png",
            "login_password_bg_portrait" to "global_assets/login_password_bg_portrait.png",
            "login_set_password_bg_portrait" to "global_assets/login_set_password_bg_portrait.png",
            "login_confirm_bg_portrait" to "global_assets/login_confirm_bg_portrait.png",
            "login_picker_bg_portrait" to "global_assets/login_picker_bg_portrait.png",
            "login_claim_bg_portrait" to "global_assets/login_claim_bg_portrait.png",
            "video_available_indicator" to "global_assets/video_available_indicator.png",
            "shop_kiosk" to "global_assets/shop_kiosk.png",
            "crafting_station" to "global_assets/crafting_station.png",
            "trainer_icon" to "global_assets/trainer_icon.png",
            "bank_vault" to "global_assets/bank_vault.png",
            "stylist_mirror" to "global_assets/stylist_mirror.png",
            "lottery_board_widget" to "global_assets/lottery_board_widget.png",
            "dice_table_widget" to "global_assets/dice_table_widget.png",
            "dungeon_portal_widget" to "global_assets/dungeon_portal_widget.png",
            "auction_hall_widget" to "global_assets/auction_hall_widget.png",
            "duel_arena_widget" to "global_assets/duel_arena_widget.png",
            "jukebox_widget" to "global_assets/jukebox_widget.png",
            "jukebox_bg" to "global_assets/jukebox_bg.png",
            // Phone-portrait companion (941×1672, 7 scrolls); the drawer prefers
            // it on portrait viewports. Absent/404 → gradient under the same
            // seated layout, like the landscape frame.
            "jukebox_bg_portrait" to "global_assets/jukebox_bg_portrait.png",
            // Music box: the rail kiosk badge and the opened device frame. The
            // device shows a play/stop control and scrolling lyrics on its face.
            "music_box_widget" to "global_assets/music_box_widget.png",
            "musicbox_bg" to "global_assets/musicbox_bg.png",
            "puzzle_kiosk" to "global_assets/puzzle_kiosk.png",
            "feature_door" to "global_assets/feature_door.png",
            "feature_container" to "global_assets/feature_container.png",
            "feature_lever" to "global_assets/feature_lever.png",
            // Server-wide default lever art (plate + rotating handle). Used for levers
            // that don't author their own plateImage/handleImage; falls back further to
            // the built-in vector lever when these aren't present either.
            "lever_plate" to "global_assets/lever_plate.png",
            "lever_handle" to "global_assets/lever_handle.png",
            // Server-wide default backdrops for the World Features modal. Used when a
            // feature doesn't author its own backgroundImage; the client falls back
            // further to a polished CSS treatment when these aren't present either.
            "container_bg" to "global_assets/container_bg.png",
            "sign_bg" to "global_assets/sign_bg.png",
            "lever_bg" to "global_assets/lever_bg.png",
            // Server-wide default door art: a static frame + a swinging leaf (mirrors
            // the lever plate/handle), the warded-seal lock overlay (glows when locked,
            // shatters on unlock), and the card backdrop. Doors that don't author their
            // own frameImage/leafImage use these; everything degrades to a CSS door.
            "door_frame" to "global_assets/door_frame.png",
            "door_leaf" to "global_assets/door_leaf.png",
            "door_lock" to "global_assets/door_lock.png",
            "door_bg" to "global_assets/door_bg.png",
            // Swirling portal shown in the doorway behind the leaf (revealed when
            // the door opens). Global only; falls back to a CSS vortex.
            "door_portal" to "global_assets/door_portal.png",
            // Server-wide default backdrop for the puzzle (grimoire/parchment) panel.
            // Used when a puzzle doesn't author its own backgroundImage; falls back
            // further to a CSS tome treatment when this isn't present either.
            "puzzle_bg" to "global_assets/puzzle_bg.png",
            // Server-wide vault interior backdrop for the bank ("The Vault") panel.
            // Global only — banks share one look; the bank_vault icon is the emblem.
            "bank_bg" to "global_assets/bank_bg.png",
            // Character panel — "Woodland Fae Cabinet". All optional; each degrades
            // to a carved-CSS fallback. bg + niche are full art; frame/plaque/charm
            // are 9-slice carved frames; the two buttons are ornate gem buttons.
            "character_bg" to "global_assets/character_bg.png",
            "character_niche" to "global_assets/character_niche.png",
            "character_frame" to "global_assets/character_frame.png",
            "character_plaque" to "global_assets/character_plaque.png",
            "character_charm" to "global_assets/character_charm.png",
            "char_btn_achievements" to "global_assets/char_btn_achievements.png",
            "char_btn_prestige" to "global_assets/char_btn_prestige.png",
            "char_btn_professions" to "global_assets/char_btn_professions.png",
            // Parchment backdrop for the pop-out describe editor.
            "character_scribe_bg" to "global_assets/character_scribe_bg.png",
            "chat_bg" to "global_assets/chat_bg.png",
            "chat_widget" to "global_assets/chat_widget.png",
            "who_bg" to "global_assets/who_bg.png",
            "who_examine_btn" to "global_assets/who_examine_btn.png",
            "who_tell_btn" to "global_assets/who_tell_btn.png",
            "who_friend_btn" to "global_assets/who_friend_btn.png",
            "guild_bg" to "global_assets/guild_bg.png",
            "friends_bg" to "global_assets/friends_bg.png",
            "group_bg" to "global_assets/group_bg.png",
            "command_reference_bg" to "global_assets/command_reference_bg.png",
            // Bestiary portrait frame — painted ornamental border overlaid on the
            // monster-manual portrait. Its opaque edge masks the rectangular
            // rare-variant colorize seam. Transparent center, ~2:3 portrait.
            "monster_manual_portrait_frame" to "global_assets/monster_manual_portrait_frame.png",
            "stylist_bg" to "global_assets/stylist_bg.png",
            "housing_bg" to "global_assets/housing_bg.png",
            "lottery_bg" to "global_assets/lottery_bg.png",
            "dice_bg" to "global_assets/dice_bg.png",
            // Phone-portrait companions (941×1672) for the fortune hall and the
            // dice table; the drawer prefers them on portrait viewports.
            "lottery_bg_portrait" to "global_assets/lottery_bg_portrait.png",
            "dice_bg_portrait" to "global_assets/dice_bg_portrait.png",
            // Aineroia's Dice — the six children, each with a themed die sprite
            // and an illustrated max face, plus the Luneqrae coin's two sides.
            // All optional; each die falls back to a themed CSS render.
            "dice_ophirae" to "global_assets/dice_ophirae.png",
            "dice_ophirae_max" to "global_assets/dice_ophirae_max.png",
            "dice_mycorae" to "global_assets/dice_mycorae.png",
            "dice_mycorae_max" to "global_assets/dice_mycorae_max.png",
            "dice_pyrae" to "global_assets/dice_pyrae.png",
            "dice_pyrae_max" to "global_assets/dice_pyrae_max.png",
            "dice_aetherae" to "global_assets/dice_aetherae.png",
            "dice_aetherae_max" to "global_assets/dice_aetherae_max.png",
            "dice_lustriae" to "global_assets/dice_lustriae.png",
            "dice_lustriae_max" to "global_assets/dice_lustriae_max.png",
            "dice_aureliae" to "global_assets/dice_aureliae.png",
            "dice_aureliae_max" to "global_assets/dice_aureliae_max.png",
            "coin_luneqrae_moon" to "global_assets/coin_luneqrae_moon.png",
            "coin_luneqrae_wind" to "global_assets/coin_luneqrae_wind.png",
            "auction_bg" to "global_assets/auction_bg.png",
            "crafting_bg" to "global_assets/crafting_bg.png",
            "professions_bg" to "global_assets/professions_bg.png",
            "admin_bg" to "global_assets/admin_bg.png",
            // Pressed-flower parchment layered behind the full-screen terminal
            // overlay (under a dark legibility scrim) once the player commits to
            // typing. Falls back to the flat translucent CSS panel when absent.
            "terminal_parchment_bg" to "global_assets/terminal_parchment_bg.png",
            "dialog_indicator" to "global_assets/dialog_indicator.png",
            "aggro_indicator" to "global_assets/aggro_indicator.png",
            "quest_available_indicator" to "global_assets/quest_available_indicator.png",
            "quest_complete_indicator" to "global_assets/quest_complete_indicator.png",
            "minimap_unexplored" to "global_assets/minimap-unexplored.png",
            "map_background" to "global_assets/map_background.png",
            // Default terrain backgrounds
            "default_bg_inside" to "defaults/bg_inside.png",
            "default_bg_outside" to "defaults/bg_outside.png",
            "default_bg_forest" to "defaults/bg_forest.png",
            "default_bg_mountain" to "defaults/bg_mountain.png",
            "default_bg_underground" to "defaults/bg_underground.png",
            "default_bg_underwater" to "defaults/bg_underwater.png",
            "default_bg_desert" to "defaults/bg_desert.png",
            "default_bg_swamp" to "defaults/bg_swamp.png",
            "default_bg_urban" to "defaults/bg_urban.png",
            "default_bg_sky" to "defaults/bg_sky.png",
            // Default mob category sprites
            "default_mob_humanoid" to "defaults/mob_humanoid.png",
            "default_mob_beast" to "defaults/mob_beast.png",
            "default_mob_undead" to "defaults/mob_undead.png",
            "default_mob_elemental" to "defaults/mob_elemental.png",
            "default_mob_construct" to "defaults/mob_construct.png",
            "default_mob_aberration" to "defaults/mob_aberration.png",
            // Default item type sprites
            "default_item_weapon" to "defaults/item_weapon.png",
            "default_item_head" to "defaults/item_head.png",
            "default_item_body" to "defaults/item_body.png",
            "default_item_hand" to "defaults/item_hand.png",
            "default_item_consumable" to "defaults/item_consumable.png",
            "default_item_generic" to "defaults/item_generic.png",
            // Default entity sprites
            "default_player" to "defaults/player.png",
            "default_ability" to "defaults/ability.png",
        )
    }
}

data class VideosConfig(
    val baseUrl: String = "/videos/",
)

data class AudioConfig(
    val baseUrl: String = "/audio/",
)

/**
 * Dialogue voice-over (text-to-speech) clips. Clips are generated and uploaded out-of-band
 * (see `docs/VOICE_OVER_CONTRACT.md`); the engine only resolves and emits clip URLs in the
 * `Dialogue.Node` GMCP package. Disabled by default so no `voiceUrl` is emitted unless a real
 * clip CDN is configured. Path shape: `<baseUrl><zone>/<templateKey>/<nodeId>.<sha8>.mp3`,
 * where `<sha8>` is the first 8 hex chars of SHA-256 over the raw node text.
 */
data class VoicesConfig(
    val enabled: Boolean = false,
    val baseUrl: String = "/voices/",
)

private fun Int.requireValidPort(fieldName: String) {
    require(this in 1..65535) { "$fieldName must be between 1 and 65535" }
}

private fun Int.requirePositive(field: String) {
    require(this > 0) { "$field must be > 0 (got $this)" }
}

private fun Long.requirePositive(field: String) {
    require(this > 0L) { "$field must be > 0 (got $this)" }
}

private fun validateMobTier(
    name: String,
    tier: MobTierConfig,
) {
    require(tier.baseHp > 0) { "ambonMUD.engine.mob.tiers.$name.baseHp must be > 0" }
    require(tier.hpScalingRate >= 1.0) { "ambonMUD.engine.mob.tiers.$name.hpScalingRate must be >= 1.0" }
    require(tier.baseMinDamage > 0) { "ambonMUD.engine.mob.tiers.$name.baseMinDamage must be > 0" }
    require(tier.baseMaxDamage >= tier.baseMinDamage) {
        "ambonMUD.engine.mob.tiers.$name.baseMaxDamage must be >= baseMinDamage"
    }
    require(tier.damageScalingRate >= 1.0) {
        "ambonMUD.engine.mob.tiers.$name.damageScalingRate must be >= 1.0"
    }
    require(tier.baseArmor >= 0) { "ambonMUD.engine.mob.tiers.$name.baseArmor must be >= 0" }
    require(tier.baseXpReward >= 0L) { "ambonMUD.engine.mob.tiers.$name.baseXpReward must be >= 0" }
    require(tier.xpScalingRate >= 1.0) { "ambonMUD.engine.mob.tiers.$name.xpScalingRate must be >= 1.0" }
    require(tier.baseGoldMin >= 0L) { "ambonMUD.engine.mob.tiers.$name.baseGoldMin must be >= 0" }
    require(tier.baseGoldMax >= tier.baseGoldMin) {
        "ambonMUD.engine.mob.tiers.$name.baseGoldMax must be >= baseGoldMin"
    }
    require(tier.goldScalingRate >= 1.0) { "ambonMUD.engine.mob.tiers.$name.goldScalingRate must be >= 1.0" }
    listOf(
        "hpScalingRate" to tier.hpScalingRate,
        "damageScalingRate" to tier.damageScalingRate,
        "xpScalingRate" to tier.xpScalingRate,
        "goldScalingRate" to tier.goldScalingRate,
    ).forEach { (label, value) ->
        if (value > MAX_SCALING_RATE) {
            logger.warn {
                "CONFIG WARNING: engine.mob.tiers.$name.$label is $value, expected <= " +
                    "$MAX_SCALING_RATE (rates above ~2x/level produce runaway growth)"
            }
        }
    }
}
