package dev.ambon.config

import dev.ambon.domain.world.Direction
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/** Maximum allowed value for per-session outbound queue capacity to prevent OOM from misconfiguration. */
private const val MAX_SESSION_OUTBOUND_QUEUE_CAPACITY = 100_000

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
        validateEngineStats()
        validateEngineAbilities()
        validateEngineStatusEffects()
        validateEnginePets()
        validateEngineBank()
        validateEngineWorldTime()
        validateEngineWeather()
        validateEngineEnchanting()
        validateEngineFactions()
        validateEngineDailyQuests()
        validateEngineAutoQuests()
        validateEngineGlobalQuests()
        validateEngineLottery()
        validateEngineGambling()
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
    }

    private fun validateEngineCombat() {
        engine.combat.maxCombatsPerTick.requirePositive("ambonMUD.engine.combat.maxCombatsPerTick")
        engine.combat.tickMillis.requirePositive("ambonMUD.engine.combat.tickMillis")
        engine.combat.minDamage.requirePositive("ambonMUD.engine.combat.minDamage")
        require(engine.combat.maxDamage >= engine.combat.minDamage) {
            "ambonMUD.engine.combat.maxDamage must be >= minDamage"
        }
        require(!engine.combat.feedback.roomBroadcastEnabled || engine.combat.feedback.enabled) {
            "ambonMUD.engine.combat.feedback.roomBroadcastEnabled requires feedback.enabled=true"
        }
    }

    private fun validateEngineRegen() {
        engine.regen.maxPlayersPerTick.requirePositive("ambonMUD.engine.regen.maxPlayersPerTick")
        engine.regen.baseIntervalMillis.requirePositive("ambonMUD.engine.regen.baseIntervalMillis")
        engine.regen.minIntervalMillis.requirePositive("ambonMUD.engine.regen.minIntervalMillis")
        engine.regen.regenAmount.requirePositive("ambonMUD.engine.regen.regenAmount")
        engine.regen.mana.baseIntervalMillis.requirePositive("ambonMUD.engine.regen.mana.baseIntervalMillis")
        engine.regen.mana.minIntervalMillis.requirePositive("ambonMUD.engine.regen.mana.minIntervalMillis")
        engine.regen.mana.regenAmount.requirePositive("ambonMUD.engine.regen.mana.regenAmount")
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
        b.meleeDamageDivisor.requirePositive("ambonMUD.engine.stats.bindings.meleeDamageDivisor")
        require(b.dodgePerPoint >= 0) { "ambonMUD.engine.stats.bindings.dodgePerPoint must be >= 0" }
        require(b.maxDodgePercent in 0..100) { "ambonMUD.engine.stats.bindings.maxDodgePercent must be in 0..100" }
        b.spellDamageDivisor.requirePositive("ambonMUD.engine.stats.bindings.spellDamageDivisor")
        b.hpScalingDivisor.requirePositive("ambonMUD.engine.stats.bindings.hpScalingDivisor")
        b.manaScalingDivisor.requirePositive("ambonMUD.engine.stats.bindings.manaScalingDivisor")
        require(b.hpRegenMsPerPoint >= 0L) { "ambonMUD.engine.stats.bindings.hpRegenMsPerPoint must be >= 0" }
        require(b.manaRegenMsPerPoint >= 0L) { "ambonMUD.engine.stats.bindings.manaRegenMsPerPoint must be >= 0" }
        require(b.xpBonusPerPoint >= 0.0) { "ambonMUD.engine.stats.bindings.xpBonusPerPoint must be >= 0" }
    }

    private fun validateEngineAbilities() {
        engine.abilities.definitions.forEach { (key, def) ->
            if (def.displayName.isBlank()) warnConfig("ability '$key' displayName is blank")
            if (def.manaCost < 0) warnConfig("ability '$key' manaCost is ${def.manaCost}, expected >= 0")
            if (def.cooldownMs < 0L) warnConfig("ability '$key' cooldownMs is ${def.cooldownMs}, expected >= 0")
            if (def.levelRequired < 1) warnConfig("ability '$key' levelRequired is ${def.levelRequired}, expected >= 1")
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
        engine.pets.definitions.forEach { (key, tmpl) ->
            require(tmpl.hp > 0) { "ambonMUD.engine.pets.definitions.$key.hp must be > 0" }
            require(tmpl.minDamage > 0) { "ambonMUD.engine.pets.definitions.$key.minDamage must be > 0" }
            require(tmpl.maxDamage >= tmpl.minDamage) {
                "ambonMUD.engine.pets.definitions.$key.maxDamage (${tmpl.maxDamage}) must be >= minDamage (${tmpl.minDamage})"
            }
            require(tmpl.armor >= 0) { "ambonMUD.engine.pets.definitions.$key.armor must be >= 0" }
        }
    }

    private fun validateEngineBank() {
        engine.bank.maxItems.requirePositive("ambonMUD.engine.bank.maxItems")
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
        require(engine.gambling.diceWinChance in 0.0..1.0) { "ambonMUD.engine.gambling.diceWinChance must be in 0.0..1.0" }
        require(engine.gambling.cooldownMs >= 0) { "ambonMUD.engine.gambling.cooldownMs must be >= 0" }
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
        require(progression.rewards.hpPerLevel >= 0) { "ambonMUD.progression.rewards.hpPerLevel must be >= 0" }
        require(progression.rewards.manaPerLevel >= 0) { "ambonMUD.progression.rewards.manaPerLevel must be >= 0" }
        require(progression.rewards.baseHp >= 1) { "ambonMUD.progression.rewards.baseHp must be >= 1" }
        require(progression.rewards.baseMana >= 0) { "ambonMUD.progression.rewards.baseMana must be >= 0" }
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
    val diceWinMultiplier: Double = 2.0,
    /** Probability of winning a dice roll (0.0–1.0). */
    val diceWinChance: Double = 0.45,
    /** Cooldown between gamble attempts in milliseconds. */
    val cooldownMs: Long = 5_000L,
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
    val hp: Int = 20,
    val minDamage: Int = 1,
    val maxDamage: Int = 4,
    val armor: Int = 0,
    val image: String? = null,
)

data class PetConfig(
    val definitions: Map<String, PetTemplateConfig> = emptyMap(),
)

data class BankConfig(
    val maxItems: Int = 50,
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

data class WeatherConfig(
    /** Minimum real-time ms between weather transitions per zone. */
    val minTransitionMs: Long = 300_000L,
    /** Maximum real-time ms between weather transitions per zone. */
    val maxTransitionMs: Long = 900_000L,
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
)

data class WorldEventsConfig(
    val definitions: Map<String, WorldEventDefinition> = emptyMap(),
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
    val worldTime: WorldTimeConfig = WorldTimeConfig(),
    val weather: WeatherConfig = WeatherConfig(),
    val worldEvents: WorldEventsConfig = WorldEventsConfig(),
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
    val sessionResumeGracePeriodMs: Long = 90_000,
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
            "help" to CommandMetadata("help/?", "Show this help", "utility"),
            "look" to CommandMetadata("look/l [target|direction]", "Look around, at a target, or in a direction", "navigation"),
            "move" to CommandMetadata("n/s/e/w/u/d", "Move in a direction", "navigation"),
            "exits" to CommandMetadata("exits/ex", "List available exits", "navigation"),
            "recall" to CommandMetadata("recall", "Return to your recall point", "navigation"),
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
            "use" to CommandMetadata("use <item>", "Use a consumable item", "items", requiresTarget = true),
            "give" to CommandMetadata("give <item> <player>", "Give an item to a player", "items", requiresTarget = true),
            "talk" to CommandMetadata("talk <npc>", "Start a conversation with an NPC", "social", requiresTarget = true),
            "kill" to CommandMetadata("kill <mob>", "Attack a mob", "combat", requiresTarget = true),
            "flee" to CommandMetadata("flee", "Attempt to flee combat", "combat"),
            "cast" to CommandMetadata("cast/c <spell> [target]", "Cast a spell or ability", "combat", requiresTarget = true),
            "spells" to CommandMetadata("spells/abilities/skills", "List your abilities", "progression"),
            "effects" to CommandMetadata("effects/buffs/debuffs", "View active status effects", "progression"),
            "score" to CommandMetadata("score/sc", "View your character sheet", "progression"),
            "balance" to CommandMetadata("gold/balance", "Check your gold", "shops"),
            "currencies" to CommandMetadata("currencies/currency/wallet", "View secondary currencies", "progression"),
            "shop_list" to CommandMetadata("list/shop", "Browse a shop's wares", "shops"),
            "buy" to CommandMetadata("buy <item>", "Purchase from a shop", "shops", requiresTarget = true),
            "sell" to CommandMetadata("sell <item>", "Sell to a shop", "shops", requiresTarget = true),
            "quest_log" to CommandMetadata("quest log/list", "View active quests", "quests"),
            "quest_info" to CommandMetadata("quest info <name>", "Quest details", "quests", requiresTarget = true),
            "quest_abandon" to CommandMetadata("quest abandon <name>", "Abandon a quest", "quests", requiresTarget = true),
            "accept" to CommandMetadata("accept <quest>", "Accept a quest from an NPC", "quests", requiresTarget = true),
            "bounty" to CommandMetadata("bounty / quest auto", "Request an auto-generated bounty quest", "quests"),
            "bounty_info" to CommandMetadata("bounty info / quest auto info", "View active bounty progress", "quests"),
            "bounty_abandon" to CommandMetadata("bounty abandon / quest auto abandon", "Abandon active bounty", "quests"),
            "achievements" to CommandMetadata("achievements/ach", "View achievements", "quests"),
            "daily" to CommandMetadata("daily/dailies", "View daily quest board", "quests"),
            "weekly" to CommandMetadata("weekly", "View weekly quest board", "quests"),
            "gquest" to CommandMetadata("gquest/gq/global", "View active global quest status", "quests"),
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
            "gather" to CommandMetadata("gather/harvest/mine <node>", "Gather from a resource node", "crafting", requiresTarget = true),
            "craft" to CommandMetadata("craft/make <recipe>", "Craft an item from a recipe", "crafting", requiresTarget = true),
            "recipes" to CommandMetadata("recipes [filter]", "Browse available recipes", "crafting"),
            "craftskills" to CommandMetadata("craftskills/professions", "View crafting skill levels", "crafting"),
            "house" to CommandMetadata("house [status]", "View your house info", "housing"),
            "house_list" to CommandMetadata("house list", "Browse room templates (at broker)", "housing"),
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
            "title" to CommandMetadata("title <titleName> | title clear", "Set or clear your title", "progression", requiresTarget = true),
            "gender" to CommandMetadata("gender <option>", "Set your gender", "progression", requiresTarget = true),
            "sprite" to CommandMetadata("sprite list | set <id> | default", "Manage your character sprite", "progression"),
            "friend" to CommandMetadata("friend list | add <player> | remove <player>", "Manage your friends list", "social"),
            "mail" to CommandMetadata("mail list | read <n> | send <player> | delete <n>", "Manage mail", "social"),
            "lottery" to CommandMetadata("lottery [info] | lottery buy [count]", "View or buy lottery tickets", "social"),
            "gamble" to CommandMetadata("gamble/dice <amount>", "Roll the dice at a tavern", "social"),
            "ansi" to CommandMetadata("ansi on/off", "Toggle color output", "utility"),
            "screenreader" to CommandMetadata("screenreader [on/off]", "Toggle screen reader mode", "utility"),
            "colors" to CommandMetadata("colors", "Preview ANSI color palette", "utility"),
            "clear" to CommandMetadata("clear", "Clear the terminal", "utility"),
            "quit" to CommandMetadata("quit/exit", "Disconnect", "utility"),
            "phase" to CommandMetadata("phase/layer [instance]", "Switch zone instance", "utility"),
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
    val backstory: String = "",
    val image: String = "",
    val selectable: Boolean = true,
    val primaryStat: String = "",
    val startRoom: String = "",
    val threatMultiplier: Double = 1.0,
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
    val manaCost: Int = 10,
    val cooldownMs: Long = 0L,
    val levelRequired: Int = 1,
    val targetType: String = "ENEMY",
    val effect: AbilityEffectConfig = AbilityEffectConfig(),
    val requiredClass: String = "",
    val image: String = "",
    val prerequisites: List<String> = emptyList(),
    val tree: String = "",
    val tier: Int = 0,
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
    /** Added damage per player level for DIRECT_DAMAGE and AREA_DAMAGE effects. */
    val damagePerLevel: Double = 0.0,
    /** Added healing per player level for DIRECT_HEAL effects. */
    val healPerLevel: Double = 0.0,
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
    /** Gold cost to unlock a new class at a trainer. */
    val goldCost: Long = 500L,
)

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
    /** Level thresholds for player sprite tiers, checked highest-first. */
    val spriteLevelTiers: List<Int> = listOf(50, 40, 30, 20, 10, 1),
    /** Human-readable names for each sprite tier, keyed by level threshold. */
    val spriteTierNames: Map<Int, String> = DEFAULT_SPRITE_TIER_NAMES,
    /** When true, auto-generates race x class x tier sprite definitions (96 images). Set false to sunset. */
    val legacyTierSprites: Boolean = true,
) {
    companion object {
        val DEFAULT_SPRITE_TIER_NAMES: Map<Int, String> = linkedMapOf(
            1 to "Novice",
            10 to "Apprentice",
            20 to "Journeyman",
            30 to "Expert",
            40 to "Master",
            50 to "Legend",
        )
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
