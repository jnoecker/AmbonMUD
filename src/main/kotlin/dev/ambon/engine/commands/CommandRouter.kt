package dev.ambon.engine.commands

import dev.ambon.domain.ids.SessionId
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
 * Use the [on] inline extension to register with reified types:
 *
 * ```kotlin
 * router.on<Command.Look> { sid, _ -> handleLook(sid) }
 * ```
 */
class CommandRouter {
    private val registry = mutableMapOf<KClass<out Command>, suspend (SessionId, Command) -> Unit>()

    @Suppress("UNCHECKED_CAST")
    fun <T : Command> on(
        type: KClass<T>,
        handler: suspend (SessionId, T) -> Unit,
    ) {
        registry[type] = handler as suspend (SessionId, Command) -> Unit
    }

    suspend fun handle(
        sessionId: SessionId,
        cmd: Command,
    ) {
        registry[cmd::class]?.invoke(sessionId, cmd)
    }
}

/** Inline convenience for registering a handler with a reified command type. */
inline fun <reified T : Command> CommandRouter.on(noinline handler: suspend (SessionId, T) -> Unit) {
    on(T::class, handler)
}
