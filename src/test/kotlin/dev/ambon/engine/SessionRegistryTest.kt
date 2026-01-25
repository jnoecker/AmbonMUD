package dev.ambon.engine

import dev.ambon.domain.ids.SessionId
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SessionRegistryTest {
    @Test
    fun `can connect and disconnect sessions`() {
        val registry = SessionRegistry()
        val sid = SessionId(123L)

        assertFalse(registry.isConnected(sid))
        registry.onConnect(sid)
        assertTrue(registry.isConnected(sid))
        registry.onDisconnect(sid)
        assertFalse(registry.isConnected(sid))
    }
}
