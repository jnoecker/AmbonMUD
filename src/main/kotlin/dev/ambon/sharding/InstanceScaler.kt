package dev.ambon.sharding

/**
 * Scaling decision produced by an [InstanceScaler].
 * These are advisory signals — an external orchestrator (or operator)
 * acts on them; the engine itself does not spawn/kill processes.
 */
sealed interface ScaleDecision {
    val zone: String
    val reason: String

    /** More instances of [zone] are needed to absorb load. */
    data class ScaleUp(
        override val zone: String,
        override val reason: String,
    ) : ScaleDecision

    /** Instance [engineId] of [zone] can be drained and stopped. */
    data class ScaleDown(
        override val zone: String,
        val engineId: String,
        override val reason: String,
    ) : ScaleDecision
}

/**
 * Evaluates zone instance data and produces scaling decisions.
 */
interface InstanceScaler {
    /** Evaluate current state and return any applicable scaling decisions. */
    fun evaluate(): List<ScaleDecision>
}
