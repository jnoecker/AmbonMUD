package dev.ambon.engine.quest

/**
 * Determines how a quest is completed once all objectives are met.
 *
 * Implementations are registered by type ID in [CompletionHandlerRegistry]
 * and dispatched by [QuestSystem].
 */
interface CompletionHandler {
    /** The completion type ID this handler matches (e.g. "auto", "npc_turn_in"). */
    val typeId: String

    /** Whether this completion type triggers automatically when all objectives are complete. */
    val autoCompletes: Boolean

    /** Whether this completion type requires talking to the quest-giver NPC. */
    val requiresNpcTurnIn: Boolean get() = false
}
