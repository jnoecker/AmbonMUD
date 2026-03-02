package dev.ambon.engine

import dev.ambon.domain.ids.MobId
import dev.ambon.engine.behavior.BehaviorTreeSystem
import dev.ambon.engine.dialogue.DialogueSystem
import dev.ambon.engine.status.StatusEffectSystem

/**
 * Centralises the mob-removal cascade that must fan out to every subsystem
 * when a mob is removed from the world outside the normal combat-kill path.
 *
 * Use [removeMobExternally] for DOT kills without a source, zone resets, and
 * admin smite. The normal combat-kill path goes through [CombatSystem] which
 * handles its own cleanup internally.
 *
 * Adding a new subsystem that tracks per-mob state? Wire it here and every
 * external removal path is updated at once.
 */
class MobRemovalCoordinator(
    private val combatSystem: CombatSystem,
    private val dialogueSystem: DialogueSystem,
    private val behaviorTreeSystem: BehaviorTreeSystem,
    private val mobs: MobRegistry,
    private val mobSystem: MobSystem,
    private val statusEffectSystem: StatusEffectSystem,
) {
    /**
     * Removes a mob from the world and cleans up all subsystem state.
     *
     * This is the canonical path for external removal (not via combat kill).
     * Call sites should handle their own broadcast/GMCP/item logic after this returns.
     */
    suspend fun removeMobExternally(mobId: MobId) {
        combatSystem.onMobRemovedExternally(mobId)
        dialogueSystem.onMobRemoved(mobId)
        behaviorTreeSystem.onMobRemoved(mobId)
        mobs.remove(mobId)
        mobSystem.onMobRemoved(mobId)
        statusEffectSystem.onMobRemoved(mobId)
    }

    /**
     * Post-kill cleanup for subsystems that are not handled by [CombatSystem] itself.
     *
     * Called from the [CombatSystem] `onMobRemoved` callback after the combat system has
     * already cleaned up combat state, removed the mob from the registry, and cleared
     * status effects.
     */
    suspend fun onCombatKillCleanup(mobId: MobId) {
        mobSystem.onMobRemoved(mobId)
        dialogueSystem.onMobRemoved(mobId)
        behaviorTreeSystem.onMobRemoved(mobId)
    }
}
