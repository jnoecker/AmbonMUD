package dev.ambon.engine.abilities

import dev.ambon.bus.OutboundBus
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.mob.MobState
import dev.ambon.engine.CombatSystem
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.PlayerState
import dev.ambon.engine.events.OutboundEvent
import java.time.Clock
import java.util.Random

class AbilitySystem(
    private val registry: AbilityRegistry,
    private val players: PlayerRegistry,
    private val combat: CombatSystem,
    private val outbound: OutboundBus,
    private val clock: Clock = Clock.systemUTC(),
    private val rng: Random = Random(),
) {
    private val learnedAbilities = mutableMapOf<SessionId, MutableSet<AbilityId>>()
    private val cooldowns = mutableMapOf<SessionId, MutableMap<AbilityId, Long>>()

    fun syncAbilities(
        sessionId: SessionId,
        level: Int,
    ) {
        val available = registry.abilitiesForLevel(level).map { it.id }.toMutableSet()
        learnedAbilities[sessionId] = available
    }

    /**
     * Syncs abilities for the given level and returns the display names of
     * any newly learned abilities (compared to what was previously known).
     */
    fun syncAbilitiesAndReturnNew(
        sessionId: SessionId,
        level: Int,
    ): List<String> {
        val previous = learnedAbilities[sessionId]?.toSet() ?: emptySet()
        val available = registry.abilitiesForLevel(level).map { it.id }.toMutableSet()
        learnedAbilities[sessionId] = available
        val newIds = available - previous
        return newIds.mapNotNull { registry.get(it)?.displayName }
    }

    suspend fun cast(
        sessionId: SessionId,
        spellName: String,
        targetKeyword: String?,
    ): String? {
        val player = players.get(sessionId) ?: return "You are not connected."
        val ability = registry.findByKeyword(spellName) ?: return "You don't know any spell called '$spellName'."
        val known = learnedAbilities[sessionId] ?: emptySet()
        if (ability.id !in known) return "You haven't learned ${ability.displayName} yet."

        if (player.mana < ability.manaCost) {
            return "Not enough mana. (${player.mana}/${ability.manaCost})"
        }

        val now = clock.millis()
        val playerCooldowns = cooldowns.getOrPut(sessionId) { mutableMapOf() }
        val cooldownExpiry = playerCooldowns[ability.id] ?: 0L
        if (now < cooldownExpiry) {
            val remaining = ((cooldownExpiry - now + 999) / 1000)
            return "${ability.displayName} is on cooldown. (${remaining}s)"
        }

        return when (ability.targetType) {
            TargetType.ENEMY -> castOnEnemy(sessionId, player, ability, targetKeyword, now, playerCooldowns)
            TargetType.SELF -> castOnSelf(sessionId, player, ability, now, playerCooldowns)
        }
    }

    fun knownAbilities(sessionId: SessionId): List<AbilityDefinition> {
        val known = learnedAbilities[sessionId] ?: return emptyList()
        return known.mapNotNull { registry.get(it) }.sortedBy { it.levelRequired }
    }

    fun cooldownRemaining(
        sessionId: SessionId,
        abilityId: AbilityId,
    ): Long {
        val now = clock.millis()
        val expiry = cooldowns[sessionId]?.get(abilityId) ?: return 0L
        return (expiry - now).coerceAtLeast(0L)
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

    private suspend fun castOnEnemy(
        sessionId: SessionId,
        player: PlayerState,
        ability: AbilityDefinition,
        targetKeyword: String?,
        now: Long,
        playerCooldowns: MutableMap<AbilityId, Long>,
    ): String? {
        val effect =
            ability.effect as? AbilityEffect.DirectDamage
                ?: return "This ability cannot target enemies."

        val mob: MobState =
            if (targetKeyword != null) {
                combat.findMobInRoom(player.roomId, targetKeyword)
                    ?: return "You don't see '$targetKeyword' here."
            } else {
                // Auto-target current combat mob
                combat.getCombatTarget(sessionId)
                    ?: return "Cast at what? Specify a target or engage in combat first."
            }

        // Deduct mana and set cooldown
        player.mana = (player.mana - ability.manaCost).coerceAtLeast(0)
        if (ability.cooldownMs > 0L) {
            playerCooldowns[ability.id] = now + ability.cooldownMs
        }

        // Roll damage (bypasses armor)
        val damage = rollRange(effect.minDamage, effect.maxDamage)
        mob.hp = (mob.hp - damage).coerceAtLeast(0)

        outbound.send(
            OutboundEvent.SendText(
                sessionId,
                "Your ${ability.displayName} hits ${mob.name} for $damage damage.",
            ),
        )
        broadcastToRoom(
            player.roomId,
            "${player.name}'s ${ability.displayName} hits ${mob.name} for $damage damage.",
            exclude = sessionId,
        )

        if (mob.hp <= 0) {
            combat.handleSpellKill(sessionId, mob)
            outbound.send(OutboundEvent.SendPrompt(sessionId))
        }

        return null
    }

    private suspend fun castOnSelf(
        sessionId: SessionId,
        player: PlayerState,
        ability: AbilityDefinition,
        now: Long,
        playerCooldowns: MutableMap<AbilityId, Long>,
    ): String? {
        val effect =
            ability.effect as? AbilityEffect.DirectHeal
                ?: return "This ability cannot target yourself."

        // Deduct mana and set cooldown
        player.mana = (player.mana - ability.manaCost).coerceAtLeast(0)
        if (ability.cooldownMs > 0L) {
            playerCooldowns[ability.id] = now + ability.cooldownMs
        }

        val heal = rollRange(effect.minHeal, effect.maxHeal)
        val before = player.hp
        player.hp = (player.hp + heal).coerceAtMost(player.maxHp)
        val actual = player.hp - before

        outbound.send(
            OutboundEvent.SendText(
                sessionId,
                "Your ${ability.displayName} heals you for $actual HP.",
            ),
        )

        return null
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
