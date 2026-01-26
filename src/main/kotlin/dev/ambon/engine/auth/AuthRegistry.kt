package dev.ambon.engine.auth

import dev.ambon.domain.ids.SessionId

class AuthRegistry {
    private val states = mutableMapOf<SessionId, ConnState>()

    fun onConnect(sessionId: SessionId) {
    }

    fun onDisconnect(sessionId: SessionId) {
        states.remove(sessionId)
    }

    fun get(sessionId: SessionId): ConnState = states[sessionId] ?: Unauthed

    fun set(
        sessionId: SessionId,
        state: ConnState,
    ) {
        states[sessionId] = state
    }
}
