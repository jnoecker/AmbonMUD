package dev.ambon.engine.events

sealed interface CombatEvent {
    data class MeleeHit(
        val targetName: String,
        val targetId: String?,
        val damage: Int,
        val sourceIsPlayer: Boolean,
    ) : CombatEvent

    data class AbilityHit(
        val abilityId: String,
        val abilityName: String,
        val targetName: String,
        val targetId: String?,
        val damage: Int,
        val sourceIsPlayer: Boolean,
    ) : CombatEvent

    data class Heal(
        val abilityName: String,
        val targetName: String,
        val amount: Int,
        val sourceIsPlayer: Boolean,
        val abilityId: String? = null,
    ) : CombatEvent

    data class Dodge(
        val targetName: String,
        val targetId: String?,
        val sourceIsPlayer: Boolean,
    ) : CombatEvent

    data class DotTick(
        val effectName: String,
        val targetName: String,
        val targetId: String?,
        val damage: Int,
    ) : CombatEvent

    data class HotTick(
        val effectName: String,
        val targetName: String,
        val amount: Int,
    ) : CombatEvent

    data class Kill(
        val targetName: String,
        val targetId: String,
        val xpGained: Long,
        val goldGained: Long,
    ) : CombatEvent

    data class Death(
        val killerName: String,
        val killerIsPlayer: Boolean,
    ) : CombatEvent

    data class ShieldAbsorb(
        val attackerName: String,
        val absorbed: Int,
        val remaining: Int,
    ) : CombatEvent

    data class PetHit(
        val petName: String,
        val targetName: String,
        val targetId: String?,
        val damage: Int,
    ) : CombatEvent

    data class PetHurt(
        val petName: String,
        val attackerName: String,
        val damage: Int,
    ) : CombatEvent

    /**
     * Cast notification for non-damaging, non-healing abilities (buffs,
     * debuffs, taunts, summons). Damage and heal abilities already emit
     * [AbilityHit] / [Heal], so this fills the gap for pure-status casts
     * that previously produced no canvas-visible event.
     *
     * [targetIsPlayer] is true when the visible target is the local player
     * (self-buffs, ally heals/buffs cast on you). When false the target is
     * an enemy (debuffs) or the caster themselves on a remote screen.
     */
    data class AbilityCast(
        val abilityId: String,
        val abilityName: String,
        val targetName: String?,
        val targetId: String?,
        val targetIsPlayer: Boolean,
        val sourceIsPlayer: Boolean,
    ) : CombatEvent
}
