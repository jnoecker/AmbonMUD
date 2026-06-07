package dev.ambon.engine

import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.persistence.InMemoryPlayerRepository
import dev.ambon.test.TestWorlds
import dev.ambon.test.buildTestPlayerRegistry
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlayerRegistryDemoTest {
    private val startRoom = TestWorlds.testWorld.startRoom

    @Test
    fun `createDemo binds an unclaimed player without persisting`() = runTest {
        val repo = InMemoryPlayerRepository()
        val registry = buildTestPlayerRegistry(startRoom, repo, ItemRegistry())

        val result = registry.createDemo(SessionId(1), "Aelara42")

        assertEquals(CreateResult.Ok, result)
        val player = registry.get(SessionId(1))
        assertNotNull(player)
        assertEquals("Aelara42", player!!.name)
        assertNull(player.playerId, "demo character should have a null playerId")
        assertNull(repo.findByName("Aelara42"), "demo character should not be persisted to the repo")
    }

    @Test
    fun `disconnect of demo character writes nothing to the repo`() = runTest {
        val repo = InMemoryPlayerRepository()
        val registry = buildTestPlayerRegistry(startRoom, repo, ItemRegistry())

        registry.createDemo(SessionId(1), "Brogan9")
        registry.disconnect(SessionId(1))

        assertNull(registry.get(SessionId(1)))
        assertNull(repo.findByName("Brogan9"))
    }

    @Test
    fun `createDemo rejects a name already online`() = runTest {
        val repo = InMemoryPlayerRepository()
        val registry = buildTestPlayerRegistry(startRoom, repo, ItemRegistry())

        registry.create(SessionId(1), "Cyrene", "password")
        val result = registry.createDemo(SessionId(2), "Cyrene")

        assertEquals(CreateResult.Taken, result)
    }

    @Test
    fun `claim with password only keeps the demo name and persists`() = runTest {
        val repo = InMemoryPlayerRepository()
        val registry = buildTestPlayerRegistry(startRoom, repo, ItemRegistry())

        registry.createDemo(SessionId(1), "Drystan3")
        val result = registry.claim(SessionId(1), newNameRaw = null, passwordRaw = "secret")

        assertTrue(result is ClaimResult.Ok)
        val player = registry.get(SessionId(1))!!
        assertEquals("Drystan3", player.name)
        assertNotNull(player.playerId, "claim should give the player a real playerId")
        val record = repo.findByName("Drystan3")
        assertNotNull(record, "claim should persist the player to the repo")
        assertTrue(record!!.passwordHash.isNotBlank(), "claim should set a password hash")
    }

    @Test
    fun `claim with new name updates both the name index and the persisted record`() = runTest {
        val repo = InMemoryPlayerRepository()
        val registry = buildTestPlayerRegistry(startRoom, repo, ItemRegistry())

        registry.createDemo(SessionId(1), "Fenris1")
        val result = registry.claim(SessionId(1), newNameRaw = "Elowen", passwordRaw = "secret")

        assertTrue(result is ClaimResult.Ok)
        val player = registry.get(SessionId(1))!!
        assertEquals("Elowen", player.name)
        // Old name should no longer resolve, new one should.
        assertNull(registry.getByName("Fenris1"), "old demo name should be unindexed after claim")
        assertEquals(SessionId(1), registry.getByName("Elowen")?.sessionId)
        assertNotNull(repo.findByName("Elowen"))
        assertNull(repo.findByName("Fenris1"), "old demo name should not have been persisted")
    }

    @Test
    fun `claim rejects a name already in the repository`() = runTest {
        val repo = InMemoryPlayerRepository()
        val registry = buildTestPlayerRegistry(startRoom, repo, ItemRegistry())

        // Pre-existing real account
        registry.create(SessionId(99), "Galen", "password")
        registry.disconnect(SessionId(99))

        registry.createDemo(SessionId(1), "Halina7")
        val result = registry.claim(SessionId(1), newNameRaw = "Galen", passwordRaw = "secret")

        assertEquals(ClaimResult.Taken, result)
        assertNull(registry.get(SessionId(1))!!.playerId, "failed claim must leave demo unchanged")
    }

    @Test
    fun `claim on an already-claimed player returns NotDemo`() = runTest {
        val repo = InMemoryPlayerRepository()
        val registry = buildTestPlayerRegistry(startRoom, repo, ItemRegistry())

        registry.create(SessionId(1), "Ivar", "password")
        val result = registry.claim(SessionId(1), newNameRaw = null, passwordRaw = "newsecret")

        assertEquals(ClaimResult.NotDemo, result)
    }

    @Test
    fun `claim rejects the reserved demo login keyword as a name`() = runTest {
        val repo = InMemoryPlayerRepository()
        val registry = buildTestPlayerRegistry(startRoom, repo, ItemRegistry())

        registry.createDemo(SessionId(1), "Keris8")
        // Any case variant of "demo" is intercepted by the login flow as the
        // guest keyword, so a claimed account with that name could never log in.
        for (reserved in listOf("demo", "Demo", "DEMO")) {
            assertEquals(
                ClaimResult.Reserved,
                registry.claim(SessionId(1), newNameRaw = reserved, passwordRaw = "secret"),
                "claim must reject reserved name '$reserved'",
            )
        }
        assertNull(registry.get(SessionId(1))!!.playerId, "failed claim must leave demo unchanged")
        assertNull(repo.findByName("Demo"))
    }

    @Test
    fun `create rejects the reserved demo login keyword as a name`() = runTest {
        val repo = InMemoryPlayerRepository()
        val registry = buildTestPlayerRegistry(startRoom, repo, ItemRegistry())

        assertEquals(CreateResult.InvalidName, registry.create(SessionId(1), "Demo", "password"))
        assertNull(repo.findByName("Demo"))
    }

    @Test
    fun `claim rejects invalid passwords and names`() = runTest {
        val repo = InMemoryPlayerRepository()
        val registry = buildTestPlayerRegistry(startRoom, repo, ItemRegistry())

        registry.createDemo(SessionId(1), "Joren5")
        assertEquals(
            ClaimResult.InvalidPassword,
            registry.claim(SessionId(1), newNameRaw = null, passwordRaw = ""),
        )
        assertEquals(
            ClaimResult.InvalidName,
            registry.claim(SessionId(1), newNameRaw = "1bad", passwordRaw = "ok"),
        )
    }
}
