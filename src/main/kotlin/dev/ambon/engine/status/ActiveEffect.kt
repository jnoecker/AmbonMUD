package dev.ambon.engine.status

import dev.ambon.domain.ids.SessionId

data class ActiveEffect(
    val definitionId: StatusEffectId,
    val appliedAtMs: Long,
    var expiresAtMs: Long,
    var lastTickAtMs: Long,
    val sourceSessionId: SessionId?,
    var shieldRemaining: Int = 0,
)

/** Immutable snapshot for display / GMCP. */
data class ActiveEffectSnapshot(
    val id: String,
    val name: String,
    val type: String,
    val remainingMs: Long,
    val stacks: Int,
)
