package dev.ambon.engine.abilities

import dev.ambon.bus.OutboundBus
import dev.ambon.domain.PlayerClass
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.CombatSystem
import dev.ambon.engine.DirtyNotifier
import dev.ambon.engine.GroupSystem
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.PlayerState
import dev.ambon.engine.broadcastToRoom
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.healHp
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.engine.resolveEffectiveStats
import dev.ambon.engine.rollRange
import dev.ambon.engine.spendMana
import dev.ambon.engine.status.StatModifiers
import dev.ambon.engine.status.StatusEffectSystem
import dev.ambon.engine.takeDamage
import java.time.Clock
import java.util.Random

class AbilitySystem(
    private val players: PlayerRegistry,
    private val registry: AbilityRegistry,
    private val outbound: OutboundBus,
    private val combat: CombatSystem,
    private val clock: Clock,
    private val rng: Random = Random(),
    private val items: ItemRegistry? = null,
    private val intSpellDivisor: Int = 3,
    private val dirtyNotifier: DirtyNotifier = DirtyNotifier.NO_OP,
    private val statusEffects: StatusEffectSystem? = null,
    private val groupSystem: GroupSystem? = null,
    private val mobs: MobRegistry? = null,
) {
    private val learnedAbilities = mutableMapOf<SessionId, MutableSet<AbilityId>>()
    private val cooldowns = mutableMapOf<SessionId, MutableMap<AbilityId, Long>>()

    fun syncAbilities(
        sessionId: SessionId,
        level: Int,
        playerClass: String? = null,
    ): List<AbilityDefinition> {
        val pc = playerClass?.let { PlayerClass.fromString(it) }
        val known = registry.abilitiesForLevelAndClass(level, pc).map { it.id }.toMutableSet()
        val previous = learnedAbilities[sessionId]
        learnedAbilities[sessionId] = known

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
        val player = players.get(sessionId) ?: return "You are not connected."

        // 1. Resolve ability
        val ability = registry.findByKeyword(spellName)
        if (ability == null || ability.id !in learnedAbilities[sessionId].orEmpty()) {
            return "You don't know a spell called '$spellName'."
        }

        // 2. Check mana
        if (player.mana < ability.manaCost) {
            return "Not enough mana. (${player.mana}/${ability.manaCost})"
        }

        // 3. Check cooldown
        val now = clock.millis()
        val expiresAt = cooldowns[sessionId]?.get(ability.id) ?: 0L
        if (now < expiresAt) {
            val remainingMs = expiresAt - now
            val remainingSec = ((remainingMs + 999) / 1000).coerceAtLeast(1)
            return "${ability.displayName} is on cooldown (${remainingSec}s remaining)."
        }

        // 4. Resolve target and apply
        when (ability.targetType) {
            TargetType.ENEMY -> {
                return handleEnemyCast(sessionId, player, ability, targetKeyword, now)
            }

            TargetType.SELF -> {
                return handleSelfCast(sessionId, player, ability, now)
            }

            TargetType.ALLY -> {
                return handleAllyCast(sessionId, player, ability, targetKeyword, now)
            }
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

        val playerEquip = items?.equipmentBonuses(player.sessionId) ?: ItemRegistry.EquipmentBonuses()
        val playerMods = statusEffects?.getPlayerStatMods(sessionId) ?: StatModifiers.ZERO
        val playerStats = resolveEffectiveStats(player, playerEquip, playerMods)

        when (val effect = ability.effect) {
            is AbilityEffect.DirectDamage -> {
                deductManaAndCooldown(sessionId, player, ability, now)
                val baseDamage = rollRange(rng, effect.minDamage, effect.maxDamage)
                val intBonus = PlayerState.statBonus(playerStats.int, intSpellDivisor)
                val damage = (baseDamage + intBonus).coerceAtLeast(1)
                mob.takeDamage(damage)
                dirtyNotifier.mobHpDirty(mob.id)
                combat.addThreat(mob.id, sessionId, damage.toDouble())
                outbound.send(
                    OutboundEvent.SendText(
                        sessionId,
                        "Your ${ability.displayName} hits ${mob.name} for $damage damage.",
                    ),
                )
                if (mob.hp <= 0) {
                    combat.handleSpellKill(sessionId, mob)
                }
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
                val intBonus = PlayerState.statBonus(playerStats.int, intSpellDivisor)
                for (m in targetMobs) {
                    val baseDamage = rollRange(rng, effect.minDamage, effect.maxDamage)
                    val damage = (baseDamage + intBonus).coerceAtLeast(1)
                    m.takeDamage(damage)
                    dirtyNotifier.mobHpDirty(m.id)
                    combat.addThreat(m.id, sessionId, damage.toDouble())
                    outbound.send(
                        OutboundEvent.SendText(
                            sessionId,
                            "Your ${ability.displayName} hits ${m.name} for $damage damage.",
                        ),
                    )
                    if (m.hp <= 0) {
                        combat.handleSpellKill(sessionId, m)
                    }
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
                val healAmount = rollRange(rng, effect.minHeal, effect.maxHeal)
                val before = player.hp
                player.healHp(healAmount)
                val healed = player.hp - before
                if (healed > 0) {
                    dirtyNotifier.playerVitalsDirty(sessionId)
                    combat.addHealingThreat(sessionId, healed)
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
                val healAmount = rollRange(rng, effect.minHeal, effect.maxHeal)
                val before = target.hp
                target.healHp(healAmount)
                val healed = target.hp - before
                if (healed > 0) {
                    dirtyNotifier.playerVitalsDirty(targetSid)
                    combat.addHealingThreat(sessionId, healed)
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

    private fun deductManaAndCooldown(
        sessionId: SessionId,
        player: PlayerState,
        ability: AbilityDefinition,
        now: Long,
    ) {
        player.spendMana(ability.manaCost)
        dirtyNotifier.playerVitalsDirty(sessionId)
        if (ability.cooldownMs > 0) {
            cooldowns.getOrPut(sessionId) { mutableMapOf() }[ability.id] = now + ability.cooldownMs
        }
    }

    fun knownAbilities(sessionId: SessionId): List<AbilityDefinition> {
        val known = learnedAbilities[sessionId] ?: return emptyList()
        return known.mapNotNull { registry.get(it) }.sortedBy { it.levelRequired }
    }

    fun cooldownRemainingMs(
        sessionId: SessionId,
        abilityId: AbilityId,
    ): Long {
        val expiresAt = cooldowns[sessionId]?.get(abilityId) ?: return 0L
        return (expiresAt - clock.millis()).coerceAtLeast(0L)
    }

    fun onPlayerDisconnected(sessionId: SessionId) {
        learnedAbilities.remove(sessionId)
        cooldowns.remove(sessionId)
    }

    fun remapSession(
        oldSid: SessionId,
        newSid: SessionId,
    ) {
        learnedAbilities.remove(oldSid)?.let { learnedAbilities[newSid] = it }
        cooldowns.remove(oldSid)?.let { cooldowns[newSid] = it }
    }
}
