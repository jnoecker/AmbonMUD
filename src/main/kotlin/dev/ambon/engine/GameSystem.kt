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
 * Tracks the session-keyed maps owned by one [GameSystem] so lifecycle fan-out
 * is automatic: register each map with [map], then delegate
 * [GameSystem.remapSession] to [remap] and [GameSystem.onPlayerDisconnected] to
 * [clear]. Adding a new per-session map can no longer silently leak state by
 * forgetting its cleanup — the map is cleaned up the moment it is registered.
 *
 * Each map is captured behind a closure with its concrete value type, so the
 * star-projection limitation that stops [remapKey] working across a
 * heterogeneous list of maps does not apply.
 */
internal class SessionScoped {
    private val clearers = mutableListOf<(SessionId) -> Unit>()
    private val remappers = mutableListOf<(SessionId, SessionId) -> Unit>()

    /** Registers and returns a fresh session-keyed map tracked for lifecycle. */
    fun <V> map(): MutableMap<SessionId, V> {
        val backing = mutableMapOf<SessionId, V>()
        clearers.add { sid -> backing.remove(sid) }
        remappers.add { old, new -> backing.remapKey(old, new) }
        return backing
    }

    /** Moves every tracked entry keyed by [oldSid] to [newSid]. */
    fun remap(oldSid: SessionId, newSid: SessionId) {
        for (remapper in remappers) remapper(oldSid, newSid)
    }

    /** Drops [sessionId] from every tracked map. */
    fun clear(sessionId: SessionId) {
        for (clearer in clearers) clearer(sessionId)
    }
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
