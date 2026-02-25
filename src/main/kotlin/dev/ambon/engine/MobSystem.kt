package dev.ambon.engine

import dev.ambon.domain.ids.MobId

/**
 * Lifecycle tracker for mobs. Movement is handled entirely by [dev.ambon.engine.behavior.BehaviorTreeSystem]
 * via behavior trees; mobs with no behavior tree are implicitly stationary.
 */
class MobSystem {
    /** Returns 0; all mob movement is driven by [dev.ambon.engine.behavior.BehaviorTreeSystem]. */
    fun tick(): Int = 0

    /** Called when a mob is removed so subsystems can clean up any per-mob state. */
    fun onMobRemoved(mobId: MobId) = Unit

    /** Called when a mob is spawned so subsystems can initialise per-mob state. */
    fun onMobSpawned(mobId: MobId) = Unit
}
