package dev.ambon.engine.events

/**
 * Each variant carries an optional [text] field with the server-rendered narrative line
 * (custom config templates, stat-formula suffixes, etc.). When present, the web client
 * combat log prefers it over its hardcoded fallback template. See
 * `web-v3/src/hooks/useGameState.ts#combatEventToLogMessage`.
 */
sealed interface CombatEvent {
    data class MeleeHit(
        val targetName: String,
        val targetId: String?,
        val damage: Int,
        val sourceIsPlayer: Boolean,
        val text: String? = null,
    ) : CombatEvent

    data class AbilityHit(
        val abilityId: String,
        val abilityName: String,
        val targetName: String,
        val targetId: String?,
        val damage: Int,
        val sourceIsPlayer: Boolean,
        val text: String? = null,
    ) : CombatEvent

    data class Heal(
        val abilityName: String,
        val targetName: String,
        val amount: Int,
        val sourceIsPlayer: Boolean,
        val abilityId: String? = null,
        val text: String? = null,
    ) : CombatEvent

    data class Dodge(
        val targetName: String,
        val targetId: String?,
        val sourceIsPlayer: Boolean,
        val text: String? = null,
    ) : CombatEvent

    data class DotTick(
        val effectName: String,
        val targetName: String,
        val targetId: String?,
        val damage: Int,
        val text: String? = null,
    ) : CombatEvent

    data class HotTick(
        val effectName: String,
        val targetName: String,
        val amount: Int,
        val text: String? = null,
    ) : CombatEvent

    data class Kill(
        val targetName: String,
        val targetId: String,
        val xpGained: Long,
        val goldGained: Long,
        val lootedItems: List<String> = emptyList(),
    ) : CombatEvent

    data class Death(
        val killerName: String,
        val killerIsPlayer: Boolean,
    ) : CombatEvent

    data class ShieldAbsorb(
        val attackerName: String,
        val absorbed: Int,
        val remaining: Int,
        val text: String? = null,
    ) : CombatEvent

    data class PetHit(
        val petName: String,
        val targetName: String,
        val targetId: String?,
        val damage: Int,
        val text: String? = null,
    ) : CombatEvent

    data class PetHurt(
        val petName: String,
        val attackerName: String,
        val damage: Int,
        val text: String? = null,
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
        val text: String? = null,
    ) : CombatEvent

    /**
     * The local player disengaged from combat. [forced] is true for the wimpy
     * auto-flee path so the client can distinguish a voluntary escape from a
     * threshold-triggered one.
     */
    data class Flee(
        val targetName: String,
        val forced: Boolean,
    ) : CombatEvent
}
