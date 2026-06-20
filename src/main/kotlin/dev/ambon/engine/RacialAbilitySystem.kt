package dev.ambon.engine

import dev.ambon.bus.OutboundBus
import dev.ambon.domain.RacialAbility
import dev.ambon.domain.RacialTrigger
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.mob.MobState
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.status.StatusEffectId
import dev.ambon.engine.status.StatusEffectSystem
import java.util.Random
import kotlin.math.roundToInt

/**
 * Combat-internal operations the [RacialAbilitySystem] can't perform on its own — they touch
 * threat tables, mob death/loot, and player attack rolls that live inside [CombatSystem].
 * [CombatSystem] implements this and is injected into the racial system after construction.
 */
interface RacialCombatBridge {
    /** Live (non-pet) enemies currently in combat with [sessionId] in the player's room. */
    fun enemiesInCombatWith(sessionId: SessionId): List<MobState>

    /** Applies [damage] to [mob] credited to [sessionId] (threat, death/loot, client sync) and
     *  shows [hitText] to the player and room. */
    suspend fun dealRacialDamage(
        sessionId: SessionId,
        mob: MobState,
        damage: Int,
        hitText: String,
    )

    /** Performs one immediate extra player melee swing against [mob]; returns true if it died. */
    suspend fun extraMeleeSwing(
        sessionId: SessionId,
        mob: MobState,
    ): Boolean

    /** Fully disengages [sessionId] from PvE combat (clears target + threat). */
    fun disengageFromCombat(sessionId: SessionId)

    /** Broadcasts [text] to everyone in [sessionId]'s room except the player. */
    suspend fun broadcastToRoomExcept(
        sessionId: SessionId,
        text: String,
    )
}

/**
 * Resolves and applies race-specific passive abilities (see [RacialAbility]). The system owns one
 * persisted cooldown per player and two combat hooks:
 *
 *  - [onPlayerSurvivedHit] fires [RacialTrigger.LOW_HEALTH] abilities after a player survives a blow
 *    with HP at or below the race's threshold.
 *  - [tryPreventLethalBlow] gives [RacialTrigger.LETHAL_BLOW] abilities the chance to negate or
 *    transform a would-be-fatal attack; returning true means the player did not die.
 *
 * Abilities currently fire in PvE combat only — PvP death keeps its existing arena-respawn flow.
 */
class RacialAbilitySystem(
    private val raceRegistry: RaceRegistry,
    private val outbound: OutboundBus,
    private val dirtyNotifier: DirtyNotifier = DirtyNotifier.NO_OP,
    private val statusEffects: StatusEffectSystem? = null,
    private val rng: Random = Random(),
    private val tickMillis: Long = 1_000L,
) {
    /** Combat operations bridge; wired by GameEngine after both systems are constructed. */
    var combatBridge: RacialCombatBridge? = null

    /**
     * Summons a racial pet. Wired by GameEngine, which knows how to scale pet stats to the owner and
     * broadcast the new mob to the room. `replaceExisting=false` lets multiple pets coexist (spores).
     * Returns the spawned pet, or null if summoning failed.
     */
    var summonRacialPet: suspend (SessionId, String, Long, Boolean) -> MobState? = { _, _, _, _ -> null }

    /** Returns the resolved racial ability for [player]'s race, or null if the race has none. */
    fun abilityFor(player: PlayerState): RacialAbility? = raceRegistry.get(player.race)?.racialAbility

    private fun onCooldown(
        player: PlayerState,
        nowMs: Long,
    ): Boolean = nowMs < player.racialAbilityCooldownUntilMs

    private fun startCooldown(
        player: PlayerState,
        ability: RacialAbility,
        nowMs: Long,
    ) {
        player.racialAbilityCooldownUntilMs = nowMs + ability.cooldownMs
        dirtyNotifier.playerVitalsDirty(player.sessionId)
    }

    /** Integer cross-multiply (matches wimpy) so a 10% threshold fires at exactly <=10% of max. */
    private fun atOrBelowThreshold(
        player: PlayerState,
        ability: RacialAbility,
    ): Boolean {
        if (ability.triggerHealthPct <= 0 || player.maxHp <= 0) return false
        return player.hp * 100 <= ability.triggerHealthPct * player.maxHp
    }

    private suspend fun announce(
        player: PlayerState,
        ability: RacialAbility,
    ) {
        if (ability.selfMessage.isNotEmpty()) {
            outbound.send(OutboundEvent.SendText(player.sessionId, ability.selfMessage))
        }
        if (ability.roomMessage.isNotEmpty()) {
            combatBridge?.broadcastToRoomExcept(player.sessionId, ability.roomMessage.replace("{player}", player.name))
        }
    }

    // ── Low-health trigger ───────────────────────────────────────────────

    /**
     * Called after [player] survives a mob hit. If their race has a low-health passive that's off
     * cooldown and their HP is at/below its threshold, the ability fires.
     */
    suspend fun onPlayerSurvivedHit(
        sessionId: SessionId,
        player: PlayerState,
        nowMs: Long,
    ) {
        val ability = abilityFor(player) ?: return
        if (ability.trigger != RacialTrigger.LOW_HEALTH) return
        if (onCooldown(player, nowMs)) return
        if (!atOrBelowThreshold(player, ability)) return
        when (ability.kind) {
            dev.ambon.domain.RacialAbilityKind.PYRAE_IMMOLATE -> fireImmolate(sessionId, player, ability, nowMs)
            dev.ambon.domain.RacialAbilityKind.AURELIA_DAZZLE -> fireDazzle(sessionId, player, ability, nowMs)
            dev.ambon.domain.RacialAbilityKind.MYCORAE_SPORES -> fireSpores(sessionId, player, ability, nowMs)
            dev.ambon.domain.RacialAbilityKind.ARCHAE_DRENGARIAE -> fireDrengariae(sessionId, player, ability, nowMs)
            dev.ambon.domain.RacialAbilityKind.OPHIRAE_WRATH -> fireWrath(sessionId, player, ability, nowMs)
            else -> Unit
        }
    }

    private suspend fun fireImmolate(
        sessionId: SessionId,
        player: PlayerState,
        ability: RacialAbility,
        nowMs: Long,
    ) {
        val bridge = combatBridge ?: return
        val enemies = bridge.enemiesInCombatWith(sessionId)
        if (enemies.isEmpty()) return
        startCooldown(player, ability, nowMs)
        announce(player, ability)
        val damage = (player.maxHp * ability.aoeDamagePctOfMaxHp).roundToInt().coerceAtLeast(1)
        for (mob in enemies) {
            bridge.dealRacialDamage(sessionId, mob, damage, "${mob.name} is engulfed in flames for $damage damage!")
        }
    }

    private suspend fun fireDazzle(
        sessionId: SessionId,
        player: PlayerState,
        ability: RacialAbility,
        nowMs: Long,
    ) {
        val bridge = combatBridge ?: return
        val stunId = ability.stunStatusId ?: return
        val enemies = bridge.enemiesInCombatWith(sessionId)
        if (enemies.isEmpty()) return
        startCooldown(player, ability, nowMs)
        announce(player, ability)
        for (mob in enemies) {
            statusEffects?.applyToMob(mob.id, StatusEffectId(stunId), casterLevel = player.level)
        }
    }

    private suspend fun fireSpores(
        sessionId: SessionId,
        player: PlayerState,
        ability: RacialAbility,
        nowMs: Long,
    ) {
        val template = ability.petTemplateKey ?: return
        startCooldown(player, ability, nowMs)
        announce(player, ability)
        val span = (ability.petCountMax - ability.petCountMin).coerceAtLeast(0)
        val count = ability.petCountMin + if (span == 0) 0 else rng.nextInt(span + 1)
        repeat(count) {
            summonRacialPet(sessionId, template, ability.petDurationMs, false)
        }
    }

    private suspend fun fireDrengariae(
        sessionId: SessionId,
        player: PlayerState,
        ability: RacialAbility,
        nowMs: Long,
    ) {
        val template = ability.petTemplateKey ?: return
        startCooldown(player, ability, nowMs)
        announce(player, ability)
        summonRacialPet(sessionId, template, ability.petDurationMs, false)
    }

    private suspend fun fireWrath(
        sessionId: SessionId,
        player: PlayerState,
        ability: RacialAbility,
        nowMs: Long,
    ) {
        startCooldown(player, ability, nowMs)
        player.damageBoostUntilMs = nowMs + ability.buffDurationMs
        player.damageBoostMultiplier = ability.damageMultiplier
        dirtyNotifier.playerVitalsDirty(sessionId)
        announce(player, ability)
    }

    // ── Lethal-blow trigger ──────────────────────────────────────────────

    /**
     * Gives a lethal-blow passive a chance to save [player] from an attack that just dropped them to
     * 0 HP. [blowDamage] is the damage the killing attack dealt and [preHitHp] is the HP they had
     * before it landed. Returns true if the player survived (the caller must then NOT kill them).
     */
    suspend fun tryPreventLethalBlow(
        sessionId: SessionId,
        player: PlayerState,
        attacker: MobState,
        blowDamage: Int,
        preHitHp: Int,
        nowMs: Long,
    ): Boolean {
        val ability = abilityFor(player) ?: return false
        if (ability.trigger != RacialTrigger.LETHAL_BLOW) return false
        if (onCooldown(player, nowMs)) return false
        return when (ability.kind) {
            dev.ambon.domain.RacialAbilityKind.KITSARAE_REVERSAL -> fireReversal(player, ability, blowDamage, preHitHp, nowMs)
            dev.ambon.domain.RacialAbilityKind.LUSTRIAE_TIMESLIP -> fireTimeslip(sessionId, player, ability, attacker, preHitHp, nowMs)
            dev.ambon.domain.RacialAbilityKind.LITHAE_STONEFORM -> fireStoneform(sessionId, player, ability, preHitHp, nowMs)
            dev.ambon.domain.RacialAbilityKind.AETHERAE_PHASE -> firePhase(player, ability, preHitHp, nowMs)
            else -> false
        }
    }

    private suspend fun fireReversal(
        player: PlayerState,
        ability: RacialAbility,
        blowDamage: Int,
        preHitHp: Int,
        nowMs: Long,
    ): Boolean {
        startCooldown(player, ability, nowMs)
        // The killing blow becomes a heal of equal magnitude: end at preHitHp + blowDamage.
        player.hp = (preHitHp + blowDamage).coerceAtMost(player.maxHp).coerceAtLeast(1)
        dirtyNotifier.playerVitalsDirty(player.sessionId)
        announce(player, ability)
        return true
    }

    private suspend fun fireTimeslip(
        sessionId: SessionId,
        player: PlayerState,
        ability: RacialAbility,
        attacker: MobState,
        preHitHp: Int,
        nowMs: Long,
    ): Boolean {
        val bridge = combatBridge ?: return false
        startCooldown(player, ability, nowMs)
        announce(player, ability)
        val killed = bridge.extraMeleeSwing(sessionId, attacker)
        return if (killed) {
            // The extra attack felled the foe before its blow could land — the player is unharmed.
            player.hp = preHitHp.coerceAtLeast(1)
            dirtyNotifier.playerVitalsDirty(sessionId)
            outbound.send(
                OutboundEvent.SendText(sessionId, "Time snaps back into place — ${attacker.name} falls and you stand unscathed."),
            )
            true
        } else {
            outbound.send(OutboundEvent.SendText(sessionId, "...but it was not enough to fell ${attacker.name}."))
            false
        }
    }

    private suspend fun fireStoneform(
        sessionId: SessionId,
        player: PlayerState,
        ability: RacialAbility,
        preHitHp: Int,
        nowMs: Long,
    ): Boolean {
        val bridge = combatBridge ?: return false
        startCooldown(player, ability, nowMs)
        bridge.disengageFromCombat(sessionId)
        val regen = (player.maxHp * ability.regenPctOfMaxHp).roundToInt()
        player.hp = (preHitHp + regen).coerceAtMost(player.maxHp).coerceAtLeast(1)
        player.untargetableUntilMs = nowMs + ability.stoneDurationMs
        // A self-applied root (existing movement gate) keeps the petrified player rooted in place.
        ability.stoneStatusId?.let { statusEffects?.applyToPlayer(sessionId, StatusEffectId(it), casterLevel = player.level) }
        dirtyNotifier.playerVitalsDirty(sessionId)
        announce(player, ability)
        return true
    }

    private suspend fun firePhase(
        player: PlayerState,
        ability: RacialAbility,
        preHitHp: Int,
        nowMs: Long,
    ): Boolean {
        startCooldown(player, ability, nowMs)
        // Phase back in with the health held before the blow; stay untargetable for a few rounds so
        // the next tick's damage is also ignored.
        player.hp = preHitHp.coerceAtLeast(1)
        player.untargetableUntilMs = nowMs + ability.phaseTicks * tickMillis
        dirtyNotifier.playerVitalsDirty(player.sessionId)
        announce(player, ability)
        return true
    }
}
