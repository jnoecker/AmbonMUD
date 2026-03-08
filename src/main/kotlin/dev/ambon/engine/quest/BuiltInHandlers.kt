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

/** Built-in collect objective handler: checks inventory count against the objective target. */
class CollectObjectiveHandlerImpl : CollectObjectiveHandler {
    override val typeId: String = "collect"

    override fun advance(
        objDef: QuestObjectiveDef,
        progress: ObjectiveProgress,
        itemId: String,
        currentInventoryCount: Int,
    ): ObjectiveProgress? {
        val targetIdRaw = objDef.targetId
        if (itemId != targetIdRaw && !itemId.endsWith(":${targetIdRaw.substringAfterLast(':')}")) return null
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
