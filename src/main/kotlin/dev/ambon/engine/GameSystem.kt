package dev.ambon.engine

import dev.ambon.domain.ids.SessionId

/**
 * Common lifecycle contract for engine subsystems that track per-session state.
 *
 * Every subsystem that maintains session-keyed maps should implement this
 * interface so [SessionLifecycleCoordinator] can iterate them uniformly.
 */
interface GameSystem {
    /** Cleans up all per-session state when a player disconnects. */
    suspend fun onPlayerDisconnected(sessionId: SessionId)

    /**
     * Transfers all per-session state from [oldSid] to [newSid] during a
     * session takeover (same account logging in from a second connection).
     *
     * The default no-op is appropriate for subsystems whose state should not
     * survive a takeover (e.g. active dialogue conversations).
     */
    fun remapSession(oldSid: SessionId, newSid: SessionId) {}
}

/** Moves the value at [oldKey] to [newKey], if present. */
internal fun <V> MutableMap<SessionId, V>.remapKey(oldKey: SessionId, newKey: SessionId) {
    remove(oldKey)?.let { this[newKey] = it }
}

/**
 * Removes [value] from the set at [key], then removes the entry entirely
 * if the set is now empty. This is the canonical room-membership cleanup.
 */
internal fun <K, V> MutableMap<K, MutableSet<V>>.removeFromSet(key: K, value: V) {
    val set = this[key] ?: return
    set.remove(value)
    if (set.isEmpty()) remove(key)
}
