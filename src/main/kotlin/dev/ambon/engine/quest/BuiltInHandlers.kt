package dev.ambon.engine.quest

import dev.ambon.domain.quest.ObjectiveProgress
import dev.ambon.domain.quest.QuestObjectiveDef

/** Built-in kill objective handler: matches when the killed mob's template key equals targetId. */
class KillObjectiveHandlerImpl : KillObjectiveHandler {
    override val typeId: String = "kill"

    override fun advance(
        objDef: QuestObjectiveDef,
        progress: ObjectiveProgress,
        killedTemplateKey: String,
    ): ObjectiveProgress? {
        if (objDef.targetId != killedTemplateKey) return null
        return progress.copy(current = progress.current + 1)
    }
}

/**
 * Collect-objective item matching: an item satisfies a collect target when its
 * id equals the target exactly, or ends with the target's `:<localId>` suffix
 * (loose-suffix matching, so any zone's copy of the same local item counts).
 * Shared by the built-in collect handler and every QuestSystem path that must
 * mirror its semantics (acceptance seeding, turn-in consumption, and the
 * Akathavae illumination bridge).
 */
fun matchesCollectTarget(
    itemId: String,
    targetId: String,
): Boolean = itemId == targetId || itemId.endsWith(":${targetId.substringAfterLast(':')}")

/** Built-in collect objective handler: checks inventory count against the objective target. */
class CollectObjectiveHandlerImpl : CollectObjectiveHandler {
    override val typeId: String = "collect"

    override fun advance(
        objDef: QuestObjectiveDef,
        progress: ObjectiveProgress,
        itemId: String,
        currentInventoryCount: Int,
    ): ObjectiveProgress? {
        if (!matchesCollectTarget(itemId, objDef.targetId)) return null
        val newCurrent = currentInventoryCount.coerceAtMost(progress.required)
        if (newCurrent <= progress.current) return null
        return progress.copy(current = newCurrent)
    }
}

/** Auto-completion: quest completes as soon as all objectives are met. */
class AutoCompletionHandler : CompletionHandler {
    override val typeId: String = "auto"
    override val autoCompletes: Boolean = true
}

/** NPC turn-in: player must talk to the quest-giver NPC to complete. */
class NpcTurnInCompletionHandler : CompletionHandler {
    override val typeId: String = "npc_turn_in"
    override val autoCompletes: Boolean = false
    override val requiresNpcTurnIn: Boolean = true
}
