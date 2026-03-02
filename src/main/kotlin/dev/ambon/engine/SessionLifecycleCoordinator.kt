package dev.ambon.engine

import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.abilities.AbilitySystem
import dev.ambon.engine.dialogue.DialogueSystem
import dev.ambon.engine.status.StatusEffectSystem

/**
 * Centralises per-session lifecycle calls that must fan out to every subsystem.
 *
 * Adding a new subsystem that tracks per-session state? Wire it here and the
 * disconnect / remap cascades are updated everywhere at once.
 */
class SessionLifecycleCoordinator(
    private val combatSystem: CombatSystem,
    private val regenSystem: RegenSystem,
    private val abilitySystem: AbilitySystem,
    private val statusEffectSystem: StatusEffectSystem,
    private val dialogueSystem: DialogueSystem,
    private val groupSystem: GroupSystem,
    private val guildSystem: GuildSystem?,
) {
    /** Cleans up all per-session state across every subsystem. */
    suspend fun onPlayerDisconnected(sessionId: SessionId) {
        combatSystem.onPlayerDisconnected(sessionId)
        regenSystem.onPlayerDisconnected(sessionId)
        abilitySystem.onPlayerDisconnected(sessionId)
        statusEffectSystem.onPlayerDisconnected(sessionId)
        dialogueSystem.onPlayerDisconnected(sessionId)
        groupSystem.onPlayerDisconnected(sessionId)
        guildSystem?.onPlayerDisconnected(sessionId)
    }

    /**
     * Transfers all per-session state from [oldSessionId] to [newSessionId]
     * during a session takeover (same account logging in from a second connection).
     */
    fun remapSession(oldSessionId: SessionId, newSessionId: SessionId) {
        combatSystem.remapSession(oldSessionId, newSessionId)
        regenSystem.remapSession(oldSessionId, newSessionId)
        abilitySystem.remapSession(oldSessionId, newSessionId)
        statusEffectSystem.remapSession(oldSessionId, newSessionId)
        groupSystem.remapSession(oldSessionId, newSessionId)
    }
}
