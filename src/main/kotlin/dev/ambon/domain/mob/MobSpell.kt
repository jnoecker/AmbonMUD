package dev.ambon.domain.mob

import dev.ambon.domain.DamageRange
import dev.ambon.engine.status.StatusEffectId

/**
 * A spell that a mob can cast during combat. Mob spells are defined per-mob
 * in zone YAML (or in pet template config) and selected by weighted random
 * each combat tick.
 */
data class MobSpell(
    val id: String,
    val displayName: String,
    /** Message shown to the target, e.g. "The shadow mage hurls a bolt of darkness at you". */
    val message: String,
    /** Message broadcast to other players in the room. Use {mob} and {target} placeholders. */
    val roomMessage: String = "",
    val damage: DamageRange? = null,
    val healMin: Int = 0,
    val healMax: Int = 0,
    val statusEffectId: StatusEffectId? = null,
    val cooldownMs: Long = 0L,
    /** Relative weight for random selection. Higher = more likely. */
    val weight: Int = 1,
)
