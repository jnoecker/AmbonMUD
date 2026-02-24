package dev.ambon.engine.abilities

import dev.ambon.bus.OutboundBus
import dev.ambon.domain.PlayerClass
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.CombatSystem
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.PlayerState
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
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

        // 4. Resolve target
        when (ability.targetType) {
            TargetType.ENEMY -> {
                val keyword =
                    targetKeyword
                        ?: if (combat.isInCombat(sessionId)) {
                            // Auto-target current combat mob
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

                // 5. Deduct mana
                player.mana = (player.mana - ability.manaCost).coerceAtLeast(0)

                // 6. Set cooldown
                if (ability.cooldownMs > 0) {
                    cooldowns.getOrPut(sessionId) { mutableMapOf() }[ability.id] = now + ability.cooldownMs
                }

                // 7. Apply damage
                val effect =
                    ability.effect as? AbilityEffect.DirectDamage
                        ?: return "Spell misconfigured (expected damage effect)."
                val baseDamage = rollRange(effect.minDamage, effect.maxDamage)
                val intBonus = intSpellBonus(player)
                val damage = (baseDamage + intBonus).coerceAtLeast(1)
                // Spell damage bypasses armor
                mob.hp = (mob.hp - damage).coerceAtLeast(0)

                outbound.send(
                    OutboundEvent.SendText(
                        sessionId,
                        "Your ${ability.displayName} hits ${mob.name} for $damage damage.",
                    ),
                )
                broadcastToRoom(
                    player.roomId,
                    "${player.name} casts ${ability.displayName}!",
                    exclude = sessionId,
                )

                // Check mob death
                if (mob.hp <= 0) {
                    combat.handleSpellKill(sessionId, mob)
                }
            }

            TargetType.SELF -> {
                // 5. Deduct mana
                player.mana = (player.mana - ability.manaCost).coerceAtLeast(0)

                // 6. Set cooldown
                if (ability.cooldownMs > 0) {
                    cooldowns.getOrPut(sessionId) { mutableMapOf() }[ability.id] = now + ability.cooldownMs
                }

                // 7. Apply heal
                val effect =
                    ability.effect as? AbilityEffect.DirectHeal
                        ?: return "Spell misconfigured (expected heal effect)."
                val healAmount = rollRange(effect.minHeal, effect.maxHeal)
                val before = player.hp
                player.hp = (player.hp + healAmount).coerceAtMost(player.maxHp)
                val healed = player.hp - before

                outbound.send(
                    OutboundEvent.SendText(
                        sessionId,
                        "Your ${ability.displayName} heals you for $healed HP.",
                    ),
                )
                broadcastToRoom(
                    player.roomId,
                    "${player.name} casts ${ability.displayName}.",
                    exclude = sessionId,
                )
            }
        }

        return null
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

    private fun intSpellBonus(player: PlayerState): Int {
        val equipInt = items?.equipment(player.sessionId)?.values?.sumOf { it.item.intelligence } ?: 0
        val totalInt = player.intelligence + equipInt
        return (totalInt - PlayerState.BASE_STAT) / intSpellDivisor
    }

    private fun rollRange(
        min: Int,
        max: Int,
    ): Int {
        if (max <= min) return min
        return min + rng.nextInt((max - min) + 1)
    }

    private suspend fun broadcastToRoom(
        roomId: RoomId,
        text: String,
        exclude: SessionId? = null,
    ) {
        for (p in players.playersInRoom(roomId)) {
            if (exclude != null && p.sessionId == exclude) continue
            outbound.send(OutboundEvent.SendText(p.sessionId, text))
        }
    }
}
