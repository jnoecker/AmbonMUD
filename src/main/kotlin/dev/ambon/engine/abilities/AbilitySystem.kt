package dev.ambon.engine.abilities

import dev.ambon.bus.OutboundBus
import dev.ambon.config.StatBindingsConfig
import dev.ambon.domain.DamageRange
import dev.ambon.domain.StatMap
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.mob.MobState
import dev.ambon.engine.CombatSystem
import dev.ambon.engine.DirtyNotifier
import dev.ambon.engine.ERR_NOT_CONNECTED
import dev.ambon.engine.GameSystem
import dev.ambon.engine.GroupSystem
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.PetSystem
import dev.ambon.engine.PlayerProgression
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.PlayerState
import dev.ambon.engine.applyHeal
import dev.ambon.engine.broadcastToRoom
import dev.ambon.engine.ceilSeconds
import dev.ambon.engine.events.CombatEvent
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.engine.remapKey
import dev.ambon.engine.resolvePlayerStats
import dev.ambon.engine.spendMana
import dev.ambon.engine.status.StatusEffectSystem
import dev.ambon.engine.takeDamage
import java.time.Clock
import java.util.Random
import kotlin.math.pow
import kotlin.math.roundToInt

class AbilitySystem(
    private val players: PlayerRegistry,
    val registry: AbilityRegistry,
    private val outbound: OutboundBus,
    private val combat: CombatSystem,
    private val clock: Clock,
    private val rng: Random = Random(),
    private val items: ItemRegistry? = null,
    private val bindings: StatBindingsConfig = StatBindingsConfig(),
    private val dirtyNotifier: DirtyNotifier = DirtyNotifier.NO_OP,
    private val statusEffects: StatusEffectSystem? = null,
    private val groupSystem: GroupSystem? = null,
    private val mobs: MobRegistry? = null,
    private val petSystem: PetSystem? = null,
    private val progression: PlayerProgression = PlayerProgression(),
    private val classRegistry: dev.ambon.engine.PlayerClassRegistry? = null,
    private val onCombatEvent: suspend (SessionId, CombatEvent) -> Unit = { _, _ -> },
    val onSummonPet: suspend (SessionId, String, Long) -> Unit = { _, _, _ -> },
) : GameSystem {
    private val manualLearnedAbilities = mutableMapOf<SessionId, MutableSet<AbilityId>>()
    private val raceGrantedAbilities = mutableMapOf<SessionId, MutableSet<AbilityId>>()
    private val effectiveAbilities = mutableMapOf<SessionId, MutableSet<AbilityId>>()
    private val cooldowns = mutableMapOf<SessionId, MutableMap<AbilityId, Long>>()

    fun syncAbilities(
        sessionId: SessionId,
        level: Int,
        playerClass: String? = null,
    ): List<AbilityDefinition> {
        val known = registry.abilitiesForLevelAndClass(level, playerClass).map { it.id }.toMutableSet()
        val previous = effectiveAbilities[sessionId]
        manualLearnedAbilities[sessionId] = known.toMutableSet()
        effectiveAbilities[sessionId] = known
        cleanupCooldowns(sessionId, known)

        // Return newly learned abilities (for notifications)
        if (previous == null) return emptyList()
        return known
            .filter { it !in previous }
            .mapNotNull { registry.get(it) }
    }

    @Suppress("CyclomaticComplexity", "LongMethod")
    suspend fun cast(
        sessionId: SessionId,
        spellName: String,
        targetKeyword: String?,
    ): String? {
        val player = players.get(sessionId) ?: return ERR_NOT_CONNECTED
        ensureAbilitiesCurrent(sessionId)

        // 1. Resolve ability
        val ability = registry.findByKeyword(spellName)
        if (ability == null || ability.id !in effectiveAbilities[sessionId].orEmpty()) {
            return "You don't know a spell called '$spellName'."
        }

        // 2. Check mana (staff have infinite mana)
        val manaCost = computeManaCost(player, ability)
        if (!player.isStaff && player.mana < manaCost) {
            return "Not enough mana. (${player.mana}/$manaCost)"
        }

        // 3. Check cooldown
        val now = clock.millis()
        val expiresAt = cooldowns[sessionId]?.get(ability.id) ?: 0L
        if (now < expiresAt) {
            val remainingSec = (expiresAt - now).ceilSeconds()
            return "${ability.displayName} is on cooldown (${remainingSec}s remaining)."
        }

        // 4. Resolve target and apply
        return when (ability.targetType) {
            "enemy" -> handleEnemyCast(sessionId, player, ability, targetKeyword, now)
            "self" -> handleSelfCast(sessionId, player, ability, now)
            "ally" -> handleAllyCast(sessionId, player, ability, targetKeyword, now)
            "all_enemies" -> handleAllEnemiesCast(sessionId, player, ability, now)
            "all_allies" -> handleAllAlliesCast(sessionId, player, ability, now)
            "pet" -> handlePetCast(sessionId, player, ability, now)
            else -> "Unknown target type '${ability.targetType}'."
        }
    }

    @Suppress("CyclomaticComplexity", "LongMethod")
    private suspend fun handleEnemyCast(
        sessionId: SessionId,
        player: PlayerState,
        ability: AbilityDefinition,
        targetKeyword: String?,
        now: Long,
    ): String? {
        val keyword =
            targetKeyword
                ?: if (combat.isInCombat(sessionId)) {
                    null
                } else {
                    return "Cast ${ability.displayName} on whom?"
                }

        val mob =
            if (keyword != null) {
                combat.findMobInRoom(player.roomId, keyword)
                    ?: return "You don't see '$keyword' here."
            } else {
                combat.getCombatTarget(sessionId)
                    ?: return "Cast ${ability.displayName} on whom?"
            }

        val playerStats = resolvePlayerStats(player, items, statusEffects, classRegistry)

        when (val effect = ability.effect) {
            is AbilityEffect.DirectDamage -> {
                deductManaAndCooldown(sessionId, player, ability, now)
                val damage = computeSpellDamage(bindings, player.level, playerStats, effect.damage, rng)
                applySpellDamage(sessionId, mob, ability, damage)
            }
            is AbilityEffect.AreaDamage -> {
                val mobRegistry = mobs ?: return "Area damage is not available."
                val groupMembers =
                    groupSystem?.membersInRoom(sessionId, player.roomId)
                        ?: listOf(sessionId)

                // Find all mobs in room that are in combat with any group member
                val targetMobs =
                    mobRegistry.mobsInRoom(player.roomId).filter { m ->
                        combat.isMobInCombat(m.id) &&
                            groupMembers.any { sid -> combat.threatTable.hasThreat(m.id, sid) }
                    }

                if (targetMobs.isEmpty()) {
                    return "No enemies in combat to hit."
                }

                deductManaAndCooldown(sessionId, player, ability, now)
                for (m in targetMobs) {
                    val damage = computeSpellDamage(bindings, player.level, playerStats, effect.damage, rng)
                    applySpellDamage(sessionId, m, ability, damage)
                }
            }
            is AbilityEffect.Taunt -> {
                if (!combat.isMobInCombat(mob.id)) {
                    return "${mob.name} is not in combat."
                }
                deductManaAndCooldown(sessionId, player, ability, now)
                val currentMax = combat.threatTable.maxThreatValue(mob.id)
                combat.threatTable.setThreat(mob.id, sessionId, currentMax + effect.margin + effect.flatThreat)
                outbound.send(
                    OutboundEvent.SendText(
                        sessionId,
                        "You taunt ${mob.name}! It turns to face you.",
                    ),
                )
                emitAbilityCast(sessionId, ability, mob.name, mob.id.value, targetIsPlayer = false)
            }
            is AbilityEffect.ApplyStatus -> {
                val sys =
                    statusEffects
                        ?: return "Status effects are not available."
                deductManaAndCooldown(sessionId, player, ability, now)
                sys.applyToMob(mob.id, effect.statusEffectId, sessionId)
                outbound.send(
                    OutboundEvent.SendText(
                        sessionId,
                        "Your ${ability.displayName} afflicts ${mob.name}!",
                    ),
                )
                emitAbilityCast(sessionId, ability, mob.name, mob.id.value, targetIsPlayer = false)
            }
            else -> return "Spell misconfigured (unexpected effect for enemy target)."
        }
        broadcastToRoom(
            players,
            outbound,
            player.roomId,
            "${player.name} casts ${ability.displayName}!",
            exclude = sessionId,
        )
        return null
    }

    private suspend fun handleSelfCast(
        sessionId: SessionId,
        player: PlayerState,
        ability: AbilityDefinition,
        now: Long,
    ): String? {
        when (val effect = ability.effect) {
            is AbilityEffect.DirectHeal -> {
                deductManaAndCooldown(sessionId, player, ability, now)
                val playerStats = resolvePlayerStats(player, items, statusEffects, classRegistry)
                val healAmount =
                    computeSpellHeal(bindings, player.level, playerStats, effect.minHeal, effect.maxHeal, rng)
                val healed = applyHeal(sessionId, player, healAmount, dirtyNotifier)
                if (healed > 0) {
                    combat.addHealingThreat(sessionId, healed)
                    onCombatEvent(
                        sessionId,
                        CombatEvent.Heal(
                            abilityName = ability.displayName,
                            targetName = player.name,
                            amount = healed,
                            sourceIsPlayer = true,
                            abilityId = ability.id.value,
                        ),
                    )
                }
                outbound.send(
                    OutboundEvent.SendText(
                        sessionId,
                        "Your ${ability.displayName} heals you for $healed HP.",
                    ),
                )
            }
            is AbilityEffect.ApplyStatus -> {
                val sys =
                    statusEffects
                        ?: return "Status effects are not available."
                deductManaAndCooldown(sessionId, player, ability, now)
                sys.applyToPlayer(sessionId, effect.statusEffectId, sessionId)
                dirtyNotifier.playerStatusDirty(sessionId)
                outbound.send(
                    OutboundEvent.SendText(
                        sessionId,
                        "You are empowered by ${ability.displayName}!",
                    ),
                )
                emitAbilityCast(sessionId, ability, player.name, null, targetIsPlayer = true)
            }
            is AbilityEffect.SummonPet -> {
                deductManaAndCooldown(sessionId, player, ability, now)
                onSummonPet(sessionId, effect.petTemplateKey, effect.durationMs)
                emitAbilityCast(sessionId, ability, player.name, null, targetIsPlayer = true)
            }
            else -> return "Spell misconfigured (unexpected effect for self target)."
        }
        broadcastToRoom(
            players,
            outbound,
            player.roomId,
            "${player.name} casts ${ability.displayName}.",
            exclude = sessionId,
        )
        return null
    }

    private suspend fun handleAllyCast(
        sessionId: SessionId,
        player: PlayerState,
        ability: AbilityDefinition,
        targetKeyword: String?,
        now: Long,
    ): String? {
        val targetSid =
            if (targetKeyword == null || targetKeyword.isBlank()) {
                sessionId
            } else {
                val targetSession =
                    players.findSessionByName(targetKeyword)
                        ?: return "Player '$targetKeyword' is not online."

                val targetPlayer = players.get(targetSession) ?: return "Player '$targetKeyword' is not online."
                if (targetPlayer.roomId != player.roomId) {
                    return "${targetPlayer.name} is not in the same room."
                }

                val group = groupSystem?.getGroup(sessionId)
                if (group != null && !group.members.contains(targetSession)) {
                    return "${targetPlayer.name} is not in your group."
                }
                if (group == null && targetSession != sessionId) {
                    return "You must be in a group to heal others."
                }
                targetSession
            }

        val target = players.get(targetSid) ?: return "Target not found."

        when (val effect = ability.effect) {
            is AbilityEffect.DirectHeal -> {
                deductManaAndCooldown(sessionId, player, ability, now)
                val playerStats = resolvePlayerStats(player, items, statusEffects, classRegistry)
                val healAmount =
                    computeSpellHeal(bindings, player.level, playerStats, effect.minHeal, effect.maxHeal, rng)
                val healed = applyHeal(targetSid, target, healAmount, dirtyNotifier)
                if (healed > 0) {
                    combat.addHealingThreat(sessionId, healed)
                    onCombatEvent(
                        sessionId,
                        CombatEvent.Heal(
                            abilityName = ability.displayName,
                            targetName = target.name,
                            amount = healed,
                            sourceIsPlayer = true,
                            abilityId = ability.id.value,
                        ),
                    )
                }
                if (targetSid == sessionId) {
                    outbound.send(
                        OutboundEvent.SendText(
                            sessionId,
                            "Your ${ability.displayName} heals you for $healed HP.",
                        ),
                    )
                } else {
                    outbound.send(
                        OutboundEvent.SendText(
                            sessionId,
                            "Your ${ability.displayName} heals ${target.name} for $healed HP.",
                        ),
                    )
                    outbound.send(
                        OutboundEvent.SendText(
                            targetSid,
                            "${player.name}'s ${ability.displayName} heals you for $healed HP.",
                        ),
                    )
                }
            }
            is AbilityEffect.ApplyStatus -> {
                val sys =
                    statusEffects
                        ?: return "Status effects are not available."
                deductManaAndCooldown(sessionId, player, ability, now)
                sys.applyToPlayer(targetSid, effect.statusEffectId, sessionId)
                dirtyNotifier.playerStatusDirty(targetSid)
                if (targetSid == sessionId) {
                    outbound.send(
                        OutboundEvent.SendText(
                            sessionId,
                            "You are empowered by ${ability.displayName}!",
                        ),
                    )
                } else {
                    outbound.send(
                        OutboundEvent.SendText(
                            sessionId,
                            "Your ${ability.displayName} empowers ${target.name}!",
                        ),
                    )
                    outbound.send(
                        OutboundEvent.SendText(
                            targetSid,
                            "${player.name}'s ${ability.displayName} empowers you!",
                        ),
                    )
                }
                emitAbilityCast(sessionId, ability, target.name, null, targetIsPlayer = (targetSid == sessionId))
            }
            else -> return "Spell misconfigured (unexpected effect for ally target)."
        }
        broadcastToRoom(
            players,
            outbound,
            player.roomId,
            "${player.name} casts ${ability.displayName}.",
            exclude = sessionId,
        )
        return null
    }

    /**
     * Heals or buffs the caster's active pet. The target is always the caster's own pet —
     * group members' pets are not addressable. Heal threat is generated against mobs already
     * engaged with the healer, mirroring the player-heal threat model.
     */
    private suspend fun handlePetCast(
        sessionId: SessionId,
        player: PlayerState,
        ability: AbilityDefinition,
        now: Long,
    ): String? {
        val pets = petSystem ?: return "Pets are not available."
        val pet = pets.getActivePet(sessionId) ?: return "You have no active pet."
        if (pet.roomId != player.roomId) return "${pet.name} is not here."

        when (val effect = ability.effect) {
            is AbilityEffect.DirectHeal -> {
                deductManaAndCooldown(sessionId, player, ability, now)
                val playerStats = resolvePlayerStats(player, items, statusEffects, classRegistry)
                val healAmount =
                    computeSpellHeal(bindings, player.level, playerStats, effect.minHeal, effect.maxHeal, rng)
                val before = pet.hp
                pet.hp = (pet.hp + healAmount).coerceAtMost(pet.maxHp)
                val healed = pet.hp - before
                if (healed > 0) {
                    dirtyNotifier.mobHpDirty(pet.id)
                    combat.addHealingThreat(sessionId, healed)
                    onCombatEvent(
                        sessionId,
                        CombatEvent.Heal(
                            abilityName = ability.displayName,
                            targetName = pet.name,
                            amount = healed,
                            sourceIsPlayer = true,
                            abilityId = ability.id.value,
                        ),
                    )
                }
                outbound.send(
                    OutboundEvent.SendText(
                        sessionId,
                        "Your ${ability.displayName} heals ${pet.name} for $healed HP.",
                    ),
                )
            }
            is AbilityEffect.ApplyStatus -> {
                val sys = statusEffects ?: return "Status effects are not available."
                deductManaAndCooldown(sessionId, player, ability, now)
                sys.applyToMob(pet.id, effect.statusEffectId, sessionId)
                outbound.send(
                    OutboundEvent.SendText(
                        sessionId,
                        "Your ${ability.displayName} empowers ${pet.name}!",
                    ),
                )
                emitAbilityCast(sessionId, ability, pet.name, pet.id.value, targetIsPlayer = false)
            }
            else -> return "Spell misconfigured (unexpected effect for pet target)."
        }
        broadcastToRoom(
            players,
            outbound,
            player.roomId,
            "${player.name} casts ${ability.displayName} on ${pet.name}.",
            exclude = sessionId,
        )
        return null
    }

    @Suppress("CyclomaticComplexity", "LongMethod")
    private suspend fun handleAllEnemiesCast(
        sessionId: SessionId,
        player: PlayerState,
        ability: AbilityDefinition,
        now: Long,
    ): String? {
        val mobRegistry = mobs ?: return "Area damage is not available."
        val groupMembers =
            groupSystem?.membersInRoom(sessionId, player.roomId)
                ?: listOf(sessionId)

        val targetMobs =
            mobRegistry.mobsInRoom(player.roomId).filter { m ->
                combat.isMobInCombat(m.id) &&
                    groupMembers.any { sid -> combat.threatTable.hasThreat(m.id, sid) }
            }

        if (targetMobs.isEmpty()) {
            return "No enemies in combat to hit."
        }

        val playerStats = resolvePlayerStats(player, items, statusEffects, classRegistry)

        when (val effect = ability.effect) {
            is AbilityEffect.DirectDamage -> {
                deductManaAndCooldown(sessionId, player, ability, now)
                for (m in targetMobs) {
                    val damage = computeSpellDamage(bindings, player.level, playerStats, effect.damage, rng)
                    applySpellDamage(sessionId, m, ability, damage)
                }
            }
            is AbilityEffect.AreaDamage -> {
                deductManaAndCooldown(sessionId, player, ability, now)
                for (m in targetMobs) {
                    val damage = computeSpellDamage(bindings, player.level, playerStats, effect.damage, rng)
                    applySpellDamage(sessionId, m, ability, damage)
                }
            }
            is AbilityEffect.ApplyStatus -> {
                val sys =
                    statusEffects
                        ?: return "Status effects are not available."
                deductManaAndCooldown(sessionId, player, ability, now)
                for (m in targetMobs) {
                    sys.applyToMob(m.id, effect.statusEffectId, sessionId)
                    emitAbilityCast(sessionId, ability, m.name, m.id.value, targetIsPlayer = false)
                }
                outbound.send(
                    OutboundEvent.SendText(
                        sessionId,
                        "Your ${ability.displayName} afflicts all enemies!",
                    ),
                )
            }
            else -> return "Spell misconfigured (unexpected effect for all_enemies target)."
        }
        broadcastToRoom(
            players,
            outbound,
            player.roomId,
            "${player.name} casts ${ability.displayName}!",
            exclude = sessionId,
        )
        return null
    }

    @Suppress("CyclomaticComplexity", "LongMethod")
    private suspend fun handleAllAlliesCast(
        sessionId: SessionId,
        player: PlayerState,
        ability: AbilityDefinition,
        now: Long,
    ): String? {
        val groupMembers =
            groupSystem?.membersInRoom(sessionId, player.roomId)
                ?: listOf(sessionId)

        when (val effect = ability.effect) {
            is AbilityEffect.DirectHeal -> {
                deductManaAndCooldown(sessionId, player, ability, now)
                val playerStats = resolvePlayerStats(player, items, statusEffects, classRegistry)
                for (targetSid in groupMembers) {
                    val target = players.get(targetSid) ?: continue
                    val healAmount =
                        computeSpellHeal(bindings, player.level, playerStats, effect.minHeal, effect.maxHeal, rng)
                    val healed = applyHeal(targetSid, target, healAmount, dirtyNotifier)
                    if (healed > 0) {
                        combat.addHealingThreat(sessionId, healed)
                        onCombatEvent(
                            sessionId,
                            CombatEvent.Heal(
                                abilityName = ability.displayName,
                                targetName = target.name,
                                amount = healed,
                                sourceIsPlayer = true,
                                abilityId = ability.id.value,
                            ),
                        )
                    }
                    if (targetSid == sessionId) {
                        outbound.send(
                            OutboundEvent.SendText(
                                sessionId,
                                "Your ${ability.displayName} heals you for $healed HP.",
                            ),
                        )
                    } else {
                        outbound.send(
                            OutboundEvent.SendText(
                                sessionId,
                                "Your ${ability.displayName} heals ${target.name} for $healed HP.",
                            ),
                        )
                        outbound.send(
                            OutboundEvent.SendText(
                                targetSid,
                                "${player.name}'s ${ability.displayName} heals you for $healed HP.",
                            ),
                        )
                    }
                }
            }
            is AbilityEffect.ApplyStatus -> {
                val sys =
                    statusEffects
                        ?: return "Status effects are not available."
                deductManaAndCooldown(sessionId, player, ability, now)
                for (targetSid in groupMembers) {
                    sys.applyToPlayer(targetSid, effect.statusEffectId, sessionId)
                    dirtyNotifier.playerStatusDirty(targetSid)
                    val targetPlayer = players.get(targetSid)
                    if (targetSid == sessionId) {
                        outbound.send(
                            OutboundEvent.SendText(
                                sessionId,
                                "You are empowered by ${ability.displayName}!",
                            ),
                        )
                    } else if (targetPlayer != null) {
                        outbound.send(
                            OutboundEvent.SendText(
                                sessionId,
                                "Your ${ability.displayName} empowers ${targetPlayer.name}!",
                            ),
                        )
                        outbound.send(
                            OutboundEvent.SendText(
                                targetSid,
                                "${player.name}'s ${ability.displayName} empowers you!",
                            ),
                        )
                    }
                    emitAbilityCast(
                        sessionId,
                        ability,
                        targetPlayer?.name ?: player.name,
                        null,
                        targetIsPlayer = (targetSid == sessionId),
                    )
                }
            }
            else -> return "Spell misconfigured (unexpected effect for all_allies target)."
        }
        broadcastToRoom(
            players,
            outbound,
            player.roomId,
            "${player.name} casts ${ability.displayName}!",
            exclude = sessionId,
        )
        return null
    }

    private suspend fun applySpellDamage(
        sessionId: SessionId,
        mob: MobState,
        ability: AbilityDefinition,
        damage: Int,
    ) {
        mob.takeDamage(damage)
        dirtyNotifier.mobHpDirty(mob.id)
        combat.addThreat(mob.id, sessionId, damage.toDouble())
        outbound.send(
            OutboundEvent.SendText(
                sessionId,
                "Your ${ability.displayName} hits ${mob.name} for $damage damage.",
            ),
        )
        onCombatEvent(
            sessionId,
            CombatEvent.AbilityHit(
                abilityId = ability.id.value,
                abilityName = ability.displayName,
                targetName = mob.name,
                targetId = mob.id.value,
                damage = damage,
                sourceIsPlayer = true,
            ),
        )
        if (mob.hp <= 0) {
            combat.handleSpellKill(sessionId, mob)
        }
    }

    private suspend fun emitAbilityCast(
        sessionId: SessionId,
        ability: AbilityDefinition,
        targetName: String?,
        targetId: String?,
        targetIsPlayer: Boolean,
    ) {
        onCombatEvent(
            sessionId,
            CombatEvent.AbilityCast(
                abilityId = ability.id.value,
                abilityName = ability.displayName,
                targetName = targetName,
                targetId = targetId,
                targetIsPlayer = targetIsPlayer,
                sourceIsPlayer = true,
            ),
        )
    }

    private suspend fun deductManaAndCooldown(
        sessionId: SessionId,
        player: PlayerState,
        ability: AbilityDefinition,
        now: Long,
    ) {
        player.spendMana(computeManaCost(player, ability))
        dirtyNotifier.playerVitalsDirty(sessionId)
        if (ability.cooldownMs > 0) {
            cooldowns.getOrPut(sessionId) { mutableMapOf() }[ability.id] = now + ability.cooldownMs
            onCooldownStarted(sessionId, ability.id.value, ability.cooldownMs)
        }
    }

    /**
     * Resolves the absolute mana cost of [ability] for [player]. Cost is computed
     * as a percentage of the player's level/class base mana pool, evaluated with
     * default INT — so stat investment grows the *pool* (more casts) rather than
     * discounting individual spells. See [AbilityDefinition.manaCostPct].
     */
    fun computeManaCost(player: PlayerState, ability: AbilityDefinition): Int {
        if (ability.manaCostPct <= 0.0) return 0
        val (_, classManaRate) = progression.resolveClassScaling(player.playerClass)
        val basePool = progression.maxManaForLevel(player.level, manaScalingRate = classManaRate)
        return ((basePool.toDouble() * ability.manaCostPct) / 100.0).roundToInt().coerceAtLeast(0)
    }

    /** Invoked after a cooldown starts; used by GameEngine to emit Char.Cooldown GMCP. */
    var onCooldownStarted: suspend (SessionId, String, Long) -> Unit = { _, _, _ -> }

    fun knownAbilityIds(sessionId: SessionId): Set<String> {
        ensureAbilitiesCurrent(sessionId)
        return effectiveAbilities[sessionId].orEmpty().map { it.value }.toSet()
    }

    fun knownAbilities(sessionId: SessionId): List<AbilityDefinition> {
        ensureAbilitiesCurrent(sessionId)
        val known = effectiveAbilities[sessionId] ?: return emptyList()
        return known.mapNotNull { registry.get(it) }.sortedBy { it.levelRequired }
    }

    /**
     * Loads abilities from a persisted set of ability IDs. Called on login instead of
     * [syncAbilities] so players only have abilities they explicitly chose at a trainer.
     *
     * [raceAbilityIds] are abilities granted by the player's current race. They are
     * kept separate from trainer-learned IDs so race swaps can revoke old grants
     * without touching anything the player learned from a trainer.
     */
    fun loadAbilities(
        sessionId: SessionId,
        learnedIds: Set<String>,
        raceAbilityIds: Set<String> = emptySet(),
    ): List<AbilityDefinition> {
        manualLearnedAbilities[sessionId] = learnedIds.map { AbilityId(it) }.toMutableSet()
        raceGrantedAbilities[sessionId] = raceAbilityIds.map { AbilityId(it) }.toMutableSet()
        return refreshKnownAbilities(sessionId)
    }

    /**
     * Replaces the set of race-granted abilities for [sessionId] and recomputes the
     * effective ability set. Trainer-learned abilities are left untouched, so any
     * ability the player also learned from a trainer survives the swap. Returns the
     * abilities the player now knows that they did not know before.
     */
    fun setRaceAbilities(
        sessionId: SessionId,
        raceAbilityIds: Set<String>,
    ): List<AbilityDefinition> {
        raceGrantedAbilities[sessionId] = raceAbilityIds.map { AbilityId(it) }.toMutableSet()
        return refreshKnownAbilities(sessionId)
    }

    /**
     * Returns the number of skill points available to spend for a player at [level]
     * after spending [spentPoints], given the configured [interval].
     * An optional [prestigeBonus] adds extra skill points from prestige perks.
     */
    fun availableSkillPoints(
        level: Int,
        spentPoints: Int,
        interval: Int,
        prestigeBonus: Int = 0,
    ): Int {
        if (interval <= 0) return 0
        return (level / interval - spentPoints + prestigeBonus).coerceAtLeast(0)
    }

    fun spentSkillPoints(learnedIds: Set<String>): Int =
        learnedIds.sumOf { learnedId ->
            registry.get(AbilityId(learnedId))?.skillPointCost ?: 0
        }

    /**
     * Returns abilities available to learn at [className]'s trainer for a player at [level]
     * who does not already know the IDs in [knownIds]. Abilities whose prerequisites are not
     * met are still included (so the UI can show them as locked) but are sorted after
     * eligible ones within the same tier.
     */
    fun trainableAbilities(
        className: String,
        level: Int,
        knownIds: Set<String>,
    ): List<AbilityDefinition> =
        registry.abilitiesForClass(className)
            .filter { it.levelRequired <= level && it.id.value !in knownIds }
            .sortedWith(
                compareBy<AbilityDefinition> { it.tier }
                    .thenBy { !registry.isPrerequisiteMet(it.id, knownIds) }
                    .thenBy { it.levelRequired },
            )

    /**
     * Entry returned by [trainerPanelAbilities]. Includes the underlying ability plus
     * per-player lock status so the UI can render locked rows with a clear reason.
     */
    data class TrainerPanelEntry(
        val ability: AbilityDefinition,
        val locked: Boolean,
        val lockReason: String?,
    )

    /**
     * Returns every ability belonging to [className] that the player does not already know,
     * annotated with lock status. Unlike [trainableAbilities], this does *not* filter out
     * abilities above the player's current level — those appear with `locked = true` and a
     * "Requires level N" reason so the training panel can show them as gated rather than
     * hiding them entirely.
     */
    fun trainerPanelAbilities(
        className: String,
        level: Int,
        knownIds: Set<String>,
    ): List<TrainerPanelEntry> =
        registry.abilitiesForClass(className)
            .filter { it.id.value !in knownIds }
            .map { ability ->
                val levelMet = ability.levelRequired <= level
                val prereqMet = registry.isPrerequisiteMet(ability.id, knownIds)
                val reason = when {
                    !levelMet -> "Requires level ${ability.levelRequired}"
                    !prereqMet -> {
                        val prereqNames = ability.prerequisites.map { pid ->
                            registry.get(pid)?.displayName ?: pid.value
                        }
                        "Requires: ${prereqNames.joinToString(", ")}"
                    }
                    else -> null
                }
                TrainerPanelEntry(
                    ability = ability,
                    locked = !levelMet || !prereqMet,
                    lockReason = reason,
                )
            }
            .sortedWith(
                compareBy<TrainerPanelEntry> { it.ability.tier }
                    .thenBy { it.locked }
                    .thenBy { it.ability.levelRequired },
            )

    /**
     * Attempts to learn [abilityId] for the session. Returns null on success, or an error message.
     * Updates the in-memory learned set; caller is responsible for persisting to [PlayerState].
     *
     * @param unlockedClasses the set of class names the player has unlocked
     * @param level current player level (for skill point calculation)
     * @param skillPointInterval from config — points earned every N levels
     * @param learnedIds the set of ability IDs already persisted on the player (for prereq checks)
     */
    fun learnAbility(
        sessionId: SessionId,
        abilityId: AbilityId,
        level: Int,
        unlockedClasses: Set<String>,
        skillPointInterval: Int,
        learnedIds: Set<String> = emptySet(),
        prestigeBonus: Int = 0,
    ): String? {
        val ability = registry.get(abilityId) ?: return "Unknown ability '${abilityId.value}'."
        ensureAbilitiesCurrent(sessionId)
        val learned = manualLearnedAbilities.getOrPut(sessionId) { mutableSetOf() }
        // Exclude race grants so a player can pay skill points to "permanently" learn an
        // ability their current race also grants — this keeps the ability across race swaps.
        val knownWithoutRace = resolveEffectiveKnownAbilities(level, unlockedClasses, learned)
        if (abilityId in knownWithoutRace) return "You already know ${ability.displayName}."
        if (ability.skillPointCost == 0) {
            return "${ability.displayName} is granted automatically when you qualify for it."
        }
        // Check prerequisites
        if (ability.prerequisites.isNotEmpty()) {
            val allLearnedIds = effectiveAbilities[sessionId].orEmpty().map { it.value }.toSet() + learnedIds
            val unmet = ability.prerequisites.filter { it.value !in allLearnedIds }
            if (unmet.isNotEmpty()) {
                val unmetNames = unmet.map { prereqId ->
                    registry.get(prereqId)?.displayName ?: prereqId.value
                }
                return "Missing prerequisites: ${unmetNames.joinToString(", ")}."
            }
        }
        val requiredClass = ability.requiredClass
        if (requiredClass != null && !unlockedClasses.any { it.equals(requiredClass, ignoreCase = true) }) {
            return "You have not unlocked the ${requiredClass.lowercase().replaceFirstChar { it.uppercaseChar() }} class."
        }
        if (ability.levelRequired > level) {
            return "You need to be level ${ability.levelRequired} to learn ${ability.displayName}."
        }
        val spent = spentSkillPoints((learned.map { it.value } + learnedIds).toSet())
        val available = availableSkillPoints(level, spent, skillPointInterval, prestigeBonus)
        if (available < ability.skillPointCost) {
            return "You need ${ability.skillPointCost} skill points to learn ${ability.displayName}."
        }
        learned.add(abilityId)
        effectiveAbilities.getOrPut(sessionId) { mutableSetOf() }.add(abilityId)
        return null
    }

    fun refreshKnownAbilities(sessionId: SessionId): List<AbilityDefinition> {
        val player = players.get(sessionId) ?: return emptyList()
        return recomputeKnownAbilities(sessionId, player.level, player.unlockedClasses)
    }

    fun recomputeKnownAbilities(
        sessionId: SessionId,
        level: Int,
        unlockedClasses: Set<String>,
    ): List<AbilityDefinition> {
        val previous = effectiveAbilities[sessionId].orEmpty()
        val manual = manualLearnedAbilities.getOrPut(sessionId) { mutableSetOf() }
        val race = raceGrantedAbilities[sessionId].orEmpty()
        val resolved = resolveEffectiveKnownAbilities(level, unlockedClasses, manual + race)
        effectiveAbilities[sessionId] = resolved.toMutableSet()
        cleanupCooldowns(sessionId, resolved)
        return resolved
            .filter { it !in previous }
            .mapNotNull { registry.get(it) }
            .sortedBy { it.levelRequired }
    }

    /**
     * Clears all manually learned abilities for a session, effectively resetting the player's
     * skill point allocations. Call [recomputeKnownAbilities] after this when zero-cost
     * auto-learned abilities should remain available.
     */
    fun clearLearnedAbilities(sessionId: SessionId) {
        manualLearnedAbilities[sessionId]?.clear()
        effectiveAbilities[sessionId]?.clear()
        cooldowns.remove(sessionId)
    }

    fun cooldownRemainingMs(
        sessionId: SessionId,
        abilityId: AbilityId,
    ): Long {
        val expiresAt = cooldowns[sessionId]?.get(abilityId) ?: return 0L
        return (expiresAt - clock.millis()).coerceAtLeast(0L)
    }

    /** Clears all ability cooldowns for a player (e.g. on death). Learned abilities are preserved. */
    fun clearCooldowns(sessionId: SessionId) {
        cooldowns.remove(sessionId)
    }

    override suspend fun onPlayerDisconnected(sessionId: SessionId) {
        manualLearnedAbilities.remove(sessionId)
        raceGrantedAbilities.remove(sessionId)
        effectiveAbilities.remove(sessionId)
        cooldowns.remove(sessionId)
    }

    override fun remapSession(
        oldSid: SessionId,
        newSid: SessionId,
    ) {
        manualLearnedAbilities.remapKey(oldSid, newSid)
        raceGrantedAbilities.remapKey(oldSid, newSid)
        effectiveAbilities.remapKey(oldSid, newSid)
        cooldowns.remapKey(oldSid, newSid)
    }

    private fun ensureAbilitiesCurrent(sessionId: SessionId) {
        val player = players.get(sessionId) ?: return
        if (manualLearnedAbilities[sessionId] == null) return
        recomputeKnownAbilities(sessionId, player.level, player.unlockedClasses)
    }

    private fun resolveEffectiveKnownAbilities(
        level: Int,
        unlockedClasses: Set<String>,
        manualIds: Set<AbilityId>,
    ): Set<AbilityId> {
        val known = manualIds.toMutableSet()

        var changed: Boolean
        do {
            changed = false
            for (ability in registry.all().sortedWith(compareBy<AbilityDefinition> { it.levelRequired }.thenBy { it.tier })) {
                if (ability.skillPointCost != 0) continue
                if (ability.levelRequired > level) continue
                if (!isClassUnlocked(ability, unlockedClasses)) continue
                if (!ability.prerequisites.all { it in known }) continue
                if (known.add(ability.id)) changed = true
            }
        } while (changed)

        return known
    }

    private fun isClassUnlocked(
        ability: AbilityDefinition,
        unlockedClasses: Set<String>,
    ): Boolean {
        val requiredClass = ability.requiredClass ?: return true
        return unlockedClasses.any { it.equals(requiredClass, ignoreCase = true) }
    }

    private fun cleanupCooldowns(
        sessionId: SessionId,
        knownIds: Set<AbilityId>,
    ) {
        val sessionCooldowns = cooldowns[sessionId] ?: return
        sessionCooldowns.entries.removeIf { (abilityId, _) -> abilityId !in knownIds }
        if (sessionCooldowns.isEmpty()) {
            cooldowns.remove(sessionId)
        }
    }
}

/**
 * Resolves a single spell-damage hit using the same shape as basic melee:
 *
 * ```
 * anchor      = (effect.damage.min + effect.damage.max) / 2
 * statBonus   = (stats[spellDamageStat] - basePoint) × spellStatMultiplier
 * levelScale  = spellLevelScalingRate ^ (level - 1)
 * core        = (anchor + statBonus) × levelScale
 * final       = round(core × uniform(spellVarianceMin, spellVarianceMax)).coerceAtLeast(1)
 * ```
 *
 * Spells bypass armor — only physical damage uses [computePlayerMeleeSwing]'s
 * armor mitigation. The authored `damage.min`/`max` range is averaged to a
 * single anchor; the variance bindings provide the roll-to-roll spread that
 * the authored range used to provide additively.
 *
 * File-level for parity with `computePlayerMeleeSwing` — keeps the spell
 * formula in one place that tests can drive directly.
 */
internal fun computeSpellDamage(
    bindings: StatBindingsConfig,
    level: Int,
    stats: StatMap,
    damage: DamageRange,
    rng: Random,
): Int {
    val anchor = (damage.min + damage.max) / 2.0
    val statTotal = stats[bindings.spellDamageStat]
    val statBonus = (statTotal - PlayerState.BASE_STAT) * bindings.spellStatMultiplier
    val levelScale = bindings.spellLevelScalingRate.pow((level - 1).coerceAtLeast(0))
    val core = (anchor + statBonus) * levelScale
    val variance = rollVariance(bindings.spellVarianceMin, bindings.spellVarianceMax, rng)
    return (core * variance).roundToInt().coerceAtLeast(1)
}

/**
 * Resolves a single heal using the same shape as [computeSpellDamage] but
 * scaled by [StatBindingsConfig.healStat] (WIS by default) instead of INT.
 */
internal fun computeSpellHeal(
    bindings: StatBindingsConfig,
    level: Int,
    stats: StatMap,
    minHeal: Int,
    maxHeal: Int,
    rng: Random,
): Int {
    val anchor = (minHeal + maxHeal) / 2.0
    val statTotal = stats[bindings.healStat]
    val statBonus = (statTotal - PlayerState.BASE_STAT) * bindings.healStatMultiplier
    val levelScale = bindings.healLevelScalingRate.pow((level - 1).coerceAtLeast(0))
    val core = (anchor + statBonus) * levelScale
    val variance = rollVariance(bindings.healVarianceMin, bindings.healVarianceMax, rng)
    return (core * variance).roundToInt().coerceAtLeast(1)
}

private fun rollVariance(min: Double, max: Double, rng: Random): Double {
    val span = max - min
    return if (span <= 0.0) min else min + rng.nextDouble() * span
}
