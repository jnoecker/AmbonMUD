package dev.ambon.engine.commands

import dev.ambon.bus.OutboundBus
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.sharding.ZoneInstance
import kotlin.reflect.KClass

/**
 * Result of a phase (layer-switch) request.
 */
sealed interface PhaseResult {
    /** Instancing is not enabled on this engine. */
    data object NotEnabled : PhaseResult

    /** Here are the instances of the player's current zone. */
    data class InstanceList(
        val currentEngineId: String,
        val instances: List<ZoneInstance>,
    ) : PhaseResult

    /** Handoff to a different instance was initiated. */
    data object Initiated : PhaseResult

    /** Already on the requested instance or no other instance available. */
    data class NoOp(
        val reason: String,
    ) : PhaseResult
}

/**
 * Thin registry-based command dispatcher.
 *
 * Handlers register themselves via [on] and are invoked by [handle].
 * After each handler returns, a [OutboundEvent.SendPrompt] is automatically
 * sent to the session unless the player is no longer in the registry or
 * [suppressAutoPrompt] was called by the handler (used for cross-zone moves
 * where the handoff process sends its own prompt).
 *
 * Use the [on] inline extension to register with reified types:
 *
 * ```kotlin
 * router.on<Command.Look> { sid, _ -> handleLook(sid) }
 * ```
 */
class CommandRouter(
    private val outbound: OutboundBus,
    private val players: PlayerRegistry,
) {
    private val registry = mutableMapOf<KClass<out Command>, suspend (SessionId, Command) -> Unit>()

    // Mutable per-invocation flag; safe because the engine runs on a single thread.
    private var autoPromptSuppressed = false

    @Suppress("UNCHECKED_CAST")
    fun <T : Command> on(
        type: KClass<T>,
        handler: suspend (SessionId, T) -> Unit,
    ) {
        registry[type] = handler as suspend (SessionId, Command) -> Unit
    }

    /**
     * Called by handlers that manage their own prompt (e.g. cross-zone moves
     * where [OutboundEvent.SendPrompt] is sent by the handoff process).
     */
    internal fun suppressAutoPrompt() {
        autoPromptSuppressed = true
    }

    suspend fun handle(
        sessionId: SessionId,
        cmd: Command,
    ) {
        autoPromptSuppressed = false
        registry[cmd::class]?.invoke(sessionId, cmd)
        if (!autoPromptSuppressed && players.get(sessionId) != null) {
            outbound.send(OutboundEvent.SendPrompt(sessionId))
        }
    }
}

/** Inline convenience for registering a handler with a reified command type. */
inline fun <reified T : Command> CommandRouter.on(noinline handler: suspend (SessionId, T) -> Unit) {
    on(T::class, handler)
}
