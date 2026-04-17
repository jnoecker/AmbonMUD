package dev.ambon.engine

import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.test.buildTestPlayerRegistry
import dev.ambon.test.loginOrFail
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Covers the cached-snapshot invariant for [PlayerRegistry.allPlayers].
 *
 * At 1k+ sessions, `allPlayers()` was the dominant per-tick allocation
 * hotspot because every call did `players.values.toList()`. The registry
 * now caches the snapshot and rebuilds it only on membership change;
 * these tests pin that behavior so future mutation sites don't silently
 * regress by forgetting to invalidate.
 */
class PlayerRegistrySnapshotTest {
    private val room = RoomId("test:start")

    @Test
    fun `allPlayers returns cached reference when membership unchanged`() =
        runTest {
            val registry = buildTestPlayerRegistry(room)
            registry.loginOrFail(SessionId(1L), "Alice")

            val first = registry.allPlayers()
            val second = registry.allPlayers()
            assertSame(first, second, "Repeated allPlayers() calls should return the same cached list")
        }

    @Test
    fun `allPlayers rebuilds after login`() =
        runTest {
            val registry = buildTestPlayerRegistry(room)

            val empty = registry.allPlayers()
            assertTrue(empty.isEmpty())

            registry.loginOrFail(SessionId(1L), "Alice")
            val afterLogin = registry.allPlayers()
            assertNotSame(empty, afterLogin, "Snapshot must be rebuilt on login")
            assertEquals(1, afterLogin.size)
            assertEquals("Alice", afterLogin[0].name)
        }

    @Test
    fun `allPlayers rebuilds after disconnect`() =
        runTest {
            val registry = buildTestPlayerRegistry(room)
            registry.loginOrFail(SessionId(1L), "Alice")
            registry.loginOrFail(SessionId(2L), "Bob")

            val twoPlayers = registry.allPlayers()
            assertEquals(2, twoPlayers.size)

            registry.disconnect(SessionId(1L))
            val afterDisconnect = registry.allPlayers()
            assertNotSame(twoPlayers, afterDisconnect, "Snapshot must be rebuilt on disconnect")
            assertEquals(1, afterDisconnect.size)
            assertEquals("Bob", afterDisconnect[0].name)
        }
}
