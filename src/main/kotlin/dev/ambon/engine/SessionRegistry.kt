package dev.ambon.engine

import dev.ambon.domain.SessionId
import java.util.concurrent.ConcurrentHashMap

class SessionRegistry {
    private val connected = ConcurrentHashMap.newKeySet<SessionId>()

    fun onConnect(id: SessionId) {
        connected.add(id)
    }

    fun onDisconnect(id: SessionId) {
        connected.remove(id)
    }
}
