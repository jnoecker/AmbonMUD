package dev.ambon.engine.quest

/**
 * Registry of objective handlers, keyed by type ID.
 * Looked up by [QuestSystem] when processing game events.
 */
class ObjectiveHandlerRegistry {
    private val killHandlers = mutableMapOf<String, KillObjectiveHandler>()
    private val collectHandlers = mutableMapOf<String, CollectObjectiveHandler>()

    fun registerKill(handler: KillObjectiveHandler) {
        killHandlers[handler.typeId] = handler
    }

    fun registerCollect(handler: CollectObjectiveHandler) {
        collectHandlers[handler.typeId] = handler
    }

    fun killHandler(typeId: String): KillObjectiveHandler? = killHandlers[typeId]

    fun collectHandler(typeId: String): CollectObjectiveHandler? = collectHandlers[typeId]

    companion object {
        /** Creates a registry pre-loaded with the built-in kill and collect handlers. */
        fun withDefaults(): ObjectiveHandlerRegistry = ObjectiveHandlerRegistry().apply {
            registerKill(KillObjectiveHandlerImpl())
            registerCollect(CollectObjectiveHandlerImpl())
        }
    }
}

/**
 * Registry of completion handlers, keyed by type ID.
 */
class CompletionHandlerRegistry {
    private val handlers = mutableMapOf<String, CompletionHandler>()

    fun register(handler: CompletionHandler) {
        handlers[handler.typeId] = handler
    }

    fun get(typeId: String): CompletionHandler? = handlers[typeId]

    companion object {
        /** Creates a registry pre-loaded with the built-in auto and npc_turn_in handlers. */
        fun withDefaults(): CompletionHandlerRegistry = CompletionHandlerRegistry().apply {
            register(AutoCompletionHandler())
            register(NpcTurnInCompletionHandler())
        }
    }
}
