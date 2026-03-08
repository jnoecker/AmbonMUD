package dev.ambon.engine.quest

import dev.ambon.domain.quest.ObjectiveProgress
import dev.ambon.domain.quest.QuestObjectiveDef

/**
 * Handles advancement of a specific quest objective type (e.g. "kill", "collect").
 *
 * Implementations are registered by type ID in [ObjectiveHandlerRegistry] and
 * dispatched by [QuestSystem] when relevant game events occur.
 */
interface ObjectiveHandler {
    /** The objective type ID this handler matches (e.g. "kill", "collect"). */
    val typeId: String
}

/**
 * Handles kill-type objectives. Invoked when a mob is killed.
 */
interface KillObjectiveHandler : ObjectiveHandler {
    fun advance(
        objDef: QuestObjectiveDef,
        progress: ObjectiveProgress,
        killedTemplateKey: String,
    ): ObjectiveProgress?
}

/**
 * Handles collect-type objectives. Invoked when an item is picked up.
 */
interface CollectObjectiveHandler : ObjectiveHandler {
    fun advance(
        objDef: QuestObjectiveDef,
        progress: ObjectiveProgress,
        itemId: String,
        currentInventoryCount: Int,
    ): ObjectiveProgress?
}
