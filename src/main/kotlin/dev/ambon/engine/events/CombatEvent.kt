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
}
