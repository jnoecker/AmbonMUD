package dev.ambon.engine.status

import dev.ambon.domain.ids.SessionId

data class ActiveEffect(
    val definitionId: StatusEffectId,
    val appliedAtMs: Long,
    var expiresAtMs: Long,
    var lastTickAtMs: Long,
    val sourceSessionId: SessionId?,
    var shieldRemaining: Int = 0,
    /**
     * Snapshot of the scaled per-tick value computed at apply time from the
     * caster's level + relevant stat using the same shape as direct
     * spell/heal damage. Null when no caster context was provided (e.g.
     * environmental or admin-applied effects); the tick loop then falls
     * back to rolling the authored `tickMinValue`..`tickMaxValue` range.
     *
     * Variance is applied per-tick (not snapshotted) so each tick still
     * rolls a fresh spread.
     */
    val tickAnchor: Double? = null,
)

/** Immutable snapshot for display / GMCP. */
data class ActiveEffectSnapshot(
    val id: String,
    val name: String,
    val type: String,
    val remainingMs: Long,
    val stacks: Int,
)
